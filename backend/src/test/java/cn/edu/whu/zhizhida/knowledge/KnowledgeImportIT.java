package cn.edu.whu.zhizhida.knowledge;
import cn.edu.whu.zhizhida.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

class KnowledgeImportIT extends IntegrationTestBase {
    static final String BASE="/api/knowledge/imports/jobs";
    @Autowired JdbcTemplate jdbc;
    @Autowired KnowledgeImportRepository repository;
    @Autowired KnowledgeService documents;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @Test void secondExecutorOnlyRecoversExpiredLeasesAndCannotCompleteAnotherExecutorsJob() throws Exception {
        user(); long owner=jdbc.queryForObject("SELECT MAX(id) FROM app_users",Long.class);
        String id=UUID.randomUUID().toString();
        repository.insert(owner,id,"lease.md",Instant.now().plusSeconds(60));
        assertThat(repository.start(id)).isTrue();
        var second=new KnowledgeImportRepository(jdbc,transactions);
        second.recover(); second.ready(second.localClaim(id,owner,"lease.md",Instant.now().plusSeconds(60)),"{}");
        assertThat(second.renew(id)).isFalse();
        assertThat(repository.find(owner,id,false).orElseThrow().job().status()).isEqualTo("RUNNING");
        jdbc.update("UPDATE knowledge_import_jobs SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",id);
        assertThat(repository.renew(id)).isFalse();
        second.recover(); repository.ready(repository.localClaim(id,owner,"lease.md",Instant.now().plusSeconds(60)),"{}");
        assertThat(repository.find(owner,id,false).orElseThrow().job().status()).isEqualTo("FAILED");
    }
    @MockitoSpyBean KnowledgeDocumentImporter importer;
    String user() throws Exception { return registerUniqueUserToken("password123","后台导入"); }
    JsonNode data(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }
    String submit(String token,String name,byte[] bytes) throws Exception {
        return data(mockMvc.perform(multipart(BASE).file(new MockMultipartFile("file",name,"application/octet-stream",bytes)).header("Authorization","Bearer "+token)).andReturn()).path("id").asText();
    }
    JsonNode waitFor(String token,String id,String status) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);JsonNode job=null;
        while(System.nanoTime()<deadline) {
            job=data(performGet(BASE+"/"+id,token));
            if(job.path("status").asText().equals(status)) return job;
            if(job.path("status").asText().equals("FAILED")) fail(job.toString());
            Thread.sleep(30);
        }
        throw new AssertionError("Task did not reach "+status+": "+job);
    }
    @Test void durablePreviewConfirmIsIdempotentAndOwnerScopedWithLongTrainingBody() throws Exception {
        String token=user(),other=user();int before=jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_documents",Integer.class);
        String id=submit(token,"长资料.docx",KnowledgeImportFixtures.docx("缓存 TTL 90 秒。\n".repeat(16000),true));
        waitFor(token,id,"READY");
        assertThat(data(performGet(BASE,token)).toString()).contains(id);
        assertThat(performGet(BASE+"/"+id,other).getResponse().getStatus()).isEqualTo(404);
        assertThat(performGet(BASE+"/"+id+"/result",other).getResponse().getStatus()).isEqualTo(404);
        assertThat(performDelete(BASE+"/"+id,other).getResponse().getStatus()).isEqualTo(404);
        var draft=data(performGet(BASE+"/"+id+"/result",token));
        assertThat(draft.path("content").asText().length()).isGreaterThan(100000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_documents",Integer.class)).isEqualTo(before);
        String corrected="# 修订资料\n\n"+"缓存 TTL 180 秒，正文🙂。\r\n\r\n".repeat(7000);
        var request=Map.of("title","已核对","sourceName","不能替换.pdf","content",corrected,"confirmed",true);
        var doc=data(performPost(BASE+"/"+id+"/confirm",request,token));
        assertThat(doc.path("content").asText()).isEqualTo(corrected);
        assertThat(doc.path("sourceName").asText()).isEqualTo("长资料.docx");
        assertThat(data(performPost(BASE+"/"+id+"/confirm",request,token)).path("id")).isEqualTo(doc.path("id"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_documents",Integer.class)).isEqualTo(before+1);
        var session=data(startInterview(Map.of("targetRole","长资料训练","trainingTopic","DATABASE","totalRounds",3,"knowledgeDocumentIds",List.of(doc.path("id").asText())),token));
        assertThat(session.path("agent").path("training").path("knowledge").get(0).path("content").asText()).isEqualTo(corrected);
        assertThat(jdbc.queryForObject("SELECT draft_json IS NULL FROM knowledge_import_jobs WHERE id=?",Boolean.class,id)).isTrue();
    }
    @Test void cancelAndFailuresCannotSaveOrLeakDraftsAndExpiredJobsDisappear() throws Exception {
        String token=user();String id=submit(token,"note.docx",KnowledgeImportFixtures.docx("待核对",false));
        waitFor(token,id,"READY");data(performDelete(BASE+"/"+id,token));
        assertThat(data(performGet(BASE+"/"+id,token)).path("status").asText()).isEqualTo("CANCELED");
        assertThat(performGet(BASE+"/"+id+"/result",token).getResponse().getStatus()).isEqualTo(409);
        assertThat(performPost(BASE+"/"+id+"/confirm",Map.of("title","x","sourceName","note.docx","content","valid","confirmed",true),token).getResponse().getStatus()).isEqualTo(409);
        String bad=submit(token,"scan.pdf",KnowledgeImportFixtures.pdf(true,false,1));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!data(performGet(BASE+"/"+bad,token)).path("status").asText().equals("FAILED") && System.nanoTime()<deadline) Thread.sleep(30);
        assertThat(data(performGet(BASE+"/"+bad,token)).path("message").asText()).contains("OCR");
        jdbc.update("UPDATE knowledge_import_jobs SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",id);
        assertThat(performGet(BASE+"/"+id,token).getResponse().getStatus()).isEqualTo(404);
        repository.expire();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_import_jobs WHERE id=?",Integer.class,id)).isZero();
        repository.insert(jdbc.queryForObject("SELECT user_id FROM knowledge_import_jobs WHERE id=?",Long.class,bad),"restart-fixture","old.pdf",Instant.now().plusSeconds(60));
        jdbc.update("UPDATE knowledge_import_jobs SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id='restart-fixture'");
        repository.recover();
        assertThat(jdbc.queryForObject("SELECT status FROM knowledge_import_jobs WHERE id='restart-fixture'",String.class)).isEqualTo("FAILED");
    }
    @Test void boundsWorkersQueueAndCancellation() throws Exception {
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        doAnswer(call-> { entered.countDown();while(!release.await(20,TimeUnit.MILLISECONDS)) { ((Runnable)call.getArgument(3)).run(); }return call.callRealMethod(); })
                .when(importer).parse(any(Path.class),startsWith("block"),any(),any());
        var ids=new ArrayList<String>();var tokens=new ArrayList<String>();String extra=user();
        try {
            for(int i=0;i<6;i++) { String token=i%3==0?user():tokens.get(tokens.size()-1);tokens.add(token);ids.add(submit(token,"block"+i+".md","valid".getBytes(StandardCharsets.UTF_8))); }
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(mockMvc.perform(multipart(BASE).file(new MockMultipartFile("file","x.md","text/plain",new byte[]{1})).header("Authorization","Bearer "+extra)).andReturn().getResponse().getStatus()).isEqualTo(429);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_import_jobs WHERE status='RUNNING'",Integer.class)).isEqualTo(2);
            data(performDelete(BASE+"/"+ids.get(5),tokens.get(5)));
            data(performDelete(BASE+"/"+ids.get(0),tokens.get(0)));
        } finally { release.countDown(); }
        for(int i=1;i<5;i++) waitFor(tokens.get(i),ids.get(i),"READY");
    }
    @Test void deadlineFailureReleasesTemporaryFiles(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        String token=user();Long userId=jdbc.queryForObject("SELECT MAX(id) FROM app_users",Long.class);
        var identity=mock(cn.edu.whu.zhizhida.auth.api.CurrentUserProvider.class);when(identity.requireUserId()).thenReturn(userId);
        var environment=new org.springframework.mock.env.MockEnvironment().withProperty("knowledge.import.directory",directory.toString()).withProperty("knowledge.import.timeout-seconds","0");
        var executor=new KnowledgeImportExecutor(repository,importer,objectMapper,new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),environment);
        var jobs=new KnowledgeImportJobs(repository,executor,documents,identity,objectMapper);
        try {
            String id=jobs.submit(new MockMultipartFile("file","timeout.md","text/plain","valid".getBytes(StandardCharsets.UTF_8))).id();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!jobs.find(id).status().equals("FAILED") && System.nanoTime()<deadline) Thread.sleep(20);
            assertThat(jobs.find(id).status()).isEqualTo("FAILED");
            assertThat(jobs.find(id).message()).contains("超时");
        } finally { executor.close(); }
        try(var paths=java.nio.file.Files.list(directory)) { assertThat(paths.toList()).isEmpty(); }
    }
    @Test void previewDoesNotPersistAndConfirmedBodyDrivesVersionsSearchCitationsAndTraining() throws Exception {
        String token=registerUniqueUserToken("password123","导入核对");
        for(String format:List.of("pdf","docx")) {
            int before=jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_documents",Integer.class);
            String job=submit(token,"source."+format,format.equals("pdf")?KnowledgeImportFixtures.pdf(false,false,1):KnowledgeImportFixtures.docx("原始正文：TTL 90 秒。",true));
            waitFor(token,job,"READY");
            var preview=data(performGet(BASE+"/"+job+"/result",token));
            assertThat(preview.path("sourceName").asText()).isEqualTo("source."+format);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_documents",Integer.class)).isEqualTo(before);
            String confirmed="# 修订笔记\r\n\r\n缓存 TTL 180 秒，corrected_"+format+"。🙂\r\n";
            var doc=data(performPost(BASE+"/"+job+"/confirm",Map.of("title","已核对正文","sourceName",preview.path("sourceName").asText(),"content",confirmed,"confirmed",true),token));
            String id=doc.path("id").asText(); assertThat(doc.path("content").asText()).isEqualTo(confirmed);
            assertThat(doc.path("version").asInt()).isEqualTo(1);
            assertThat(doc.path("sourceName").asText()).isEqualTo("source."+format);
            for(var chunk:doc.path("chunks")) assertThat(chunk.path("content").asText()).isEqualTo(confirmed.substring(chunk.path("startOffset").asInt(),chunk.path("endOffset").asInt()));
            assertThat(data(performGet("/api/knowledge/search?query=corrected_"+format,token))).isNotEmpty();
            var answer=data(performPost("/api/knowledge/answers",Map.of("question","TTL","documentIds",List.of(id)),token));
            var citation=answer.path("claims").get(0).path("citations").get(0);
            assertThat(confirmed).contains(citation.path("quote").asText());
            var session=data(startInterview(Map.of("targetRole","导入训练","trainingTopic","DATABASE","totalRounds",3,"knowledgeDocumentIds",List.of(id)),token));
            var snapshot=session.path("agent").path("training").path("knowledge").get(0);
            assertThat(snapshot.path("content").asText()).isEqualTo(confirmed);
            assertThat(snapshot.path("sourceName").asText()).isEqualTo("source."+format);
            var edited=data(performPut("/api/knowledge/documents/"+id+"/content",Map.of("content","缓存 TTL 300 秒。","expectedVersion",1),token));
            assertThat(edited.path("version").asInt()).isEqualTo(2);
            assertThat(data(performGet("/api/knowledge/documents/"+id+"/versions/1",token)).path("content").asText()).isEqualTo(confirmed);
        }
    }
    @Test void failuresAndUnconfirmedRequestsCannotCreateDocuments() throws Exception {
        String token=registerUniqueUserToken("password123","失败提示");
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_documents",Integer.class);
        for(var file:List.of(new MockMultipartFile("file","scan.pdf","application/pdf",KnowledgeImportFixtures.pdf(true,false,1)),
                new MockMultipartFile("file","locked.docx","application/octet-stream",KnowledgeImportFixtures.encryptedDocx()),
                new MockMultipartFile("file","empty.docx","application/octet-stream",KnowledgeImportFixtures.docx("",false)))) {
            String failed=submit(token,file.getOriginalFilename(),file.getBytes());
            waitFor(token,failed,"FAILED");
            assertThat(performGet(BASE+"/"+failed+"/result",token).getResponse().getStatus()).isEqualTo(409);
        }
        var large=new MockMultipartFile("file","large.docx","application/octet-stream",new byte[31457281]);
        assertThat(mockMvc.perform(multipart(BASE).file(large).header("Authorization","Bearer "+token)).andReturn().getResponse().getStatus()).isEqualTo(400);
        String ready=submit(token,"x.pdf",KnowledgeImportFixtures.pdf(false,false,1));
        waitFor(token,ready,"READY");
        for(var confirmation:List.of(Map.of("title","未确认","sourceName","x.pdf","content","有效正文"),Map.of("title","未确认","sourceName","x.pdf","content","有效正文","confirmed",false),Map.of("title","空正文","sourceName","x.pdf","content"," ","confirmed",true)))
            assertThat(performPost(BASE+"/"+ready+"/confirm",confirmation,token).getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_documents",Integer.class)).isEqualTo(before);
        var file=new MockMultipartFile("file","x.pdf","application/pdf",KnowledgeImportFixtures.pdf(false,false,1));
        assertThat(mockMvc.perform(multipart(BASE).file(file)).andReturn().getResponse().getStatus()).isEqualTo(401);
    }
}
