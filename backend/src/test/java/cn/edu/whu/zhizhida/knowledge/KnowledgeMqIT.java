package cn.edu.whu.zhizhida.knowledge;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import cn.edu.whu.zhizhida.ai.api.KnowledgeVectors;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.*;
import java.util.*;
import static org.mockito.Mockito.*;

class KnowledgeMqIT {
    abstract static class Imports extends IntegrationTestBase {
        @Autowired KnowledgeImportRepository repository;
        @Autowired JdbcTemplate jdbc;
        @MockitoBean KnowledgeVectors vectors;
        String token;
        long owner;
        @BeforeEach void registerOwner() throws Exception {
            token=registerUniqueUserToken("password123","持久导入");
            owner=objectMapper.readTree(performGet("/api/users/me",token).getResponse().getContentAsString()).at("/data/id").asLong();
        }
        @AfterEach void deleteOwner() throws Exception {
            performDeleteWithBody("/api/users/me",Map.of("currentPassword","password123"),token);
        }
        String submit() throws Exception {
            var response=mockMvc.perform(multipart("/api/knowledge/imports/jobs")
                    .file(new MockMultipartFile("file","note.md","text/plain","持久化资料正文".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .header("Authorization","Bearer "+token)).andReturn();
            assertThat(response.getResponse().getStatus()).as(response.getResponse().getContentAsString()).isEqualTo(200);
            return objectMapper.readTree(response.getResponse().getContentAsString()).at("/data/id").asText();
        }
        String status(String id) { return jdbc.queryForObject("SELECT status FROM knowledge_import_jobs WHERE id=?",String.class,id); }
    }

    @Nested
    @DirtiesContext
    @SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
            "knowledge.dispatch.mode=rabbitmq","app.run-dispatch.mode=rabbitmq",
            "knowledge.dispatch.publisher-enabled=false","knowledge.import.worker-enabled=false",
            "agent.vector.enabled=true","agent.vector.worker-enabled=false",
            "app.outbox.publisher.enabled=false","app.run-recovery.enabled=false","interview.runs.worker-enabled=false",
            "spring.rabbitmq.dynamic=false"})
    class SharedFiles extends Imports {
        @TempDir static Path shared;
        @DynamicPropertySource static void properties(DynamicPropertyRegistry p) { p.add("knowledge.import.shared-directory",()->shared.toString()); }
        @MockitoBean RabbitTemplate rabbit;
        @Autowired KnowledgeRepository documents;
        @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
        @Autowired KnowledgeImportExecutor executor;
        @BeforeEach void configureVectors() { when(vectors.fingerprint()).thenReturn("durable-import-fixture"); }
        @Test void switchingToMqReleasesAbandonedLocalUploadsAndRetainsUsableDrafts() throws Exception {
            String abandoned = java.util.UUID.randomUUID().toString();
            String active = java.util.UUID.randomUUID().toString();
            String draft = java.util.UUID.randomUUID().toString();
            for (String id : java.util.List.of(abandoned, active, draft)) {
                repository.insert(owner, id, "local.md", java.time.Instant.now().plusSeconds(600));
            }
            jdbc.update("UPDATE knowledge_import_jobs SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?", abandoned);
            jdbc.update("UPDATE knowledge_import_jobs SET status='READY',draft_json='{}',lease_until=NULL WHERE id=?", draft);

            repository.recoverShared();
            assertThat(status(abandoned)).isEqualTo("FAILED");
            assertThat(status(active)).isEqualTo("QUEUED");
            assertThat(status(draft)).isEqualTo("READY");
            String queued = submit(); // 废弃的本地任务已释放第三个上传名额。
            assertThat(repository.pending()).contains(queued).doesNotContain(active, abandoned, draft);

            jdbc.update("UPDATE knowledge_import_jobs SET expires_at=CURRENT_TIMESTAMP(6) WHERE id=?", draft);
            repository.recoverShared();
            assertThat(repository.find(owner, draft, false)).isEmpty();
        }
        @Test void survivesApiExecutorReplacementAndRequiresIdempotentUserConfirmation() throws Exception {
            String id=submit();
            assertThat(status(id)).isEqualTo("QUEUED");
            assertThat(shared.resolve(id).resolve("source.upload")).exists();
            repository.recover();assertThat(status(id)).isEqualTo("QUEUED");
            executor.execute(id);executor.execute(id);
            assertThat(status(id)).isEqualTo("READY");
            assertThat(jdbc.queryForObject("SELECT attempts FROM knowledge_import_jobs WHERE id=?",Integer.class,id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_documents WHERE user_id=?",Integer.class,owner)).isZero();
            var body=Map.of("title","核对后资料","sourceName","note.md","content","修订正文","confirmed",true);
            var first=performPost("/api/knowledge/imports/jobs/"+id+"/confirm",body,token);
            var second=performPost("/api/knowledge/imports/jobs/"+id+"/confirm",body,token);
            assertThat(first.getResponse().getStatus()).isEqualTo(200);
            assertThat(objectMapper.readTree(first.getResponse().getContentAsString()).at("/data/id"))
                    .isEqualTo(objectMapper.readTree(second.getResponse().getContentAsString()).at("/data/id"));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_documents WHERE user_id=?",Integer.class,owner)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_index_jobs j JOIN knowledge_documents d ON d.id=j.source_id WHERE d.user_id=? AND j.state='QUEUED'",Integer.class,owner)).isEqualTo(1);
        }
        @Test void expiredLeaseRetriesWithNewTokenAndOldExecutorCannotCommitOrFailIt() throws Exception {
            String id=submit();var first=repository.claim(id).orElseThrow();
            assertThat(repository.claim(id)).isEmpty();
            jdbc.update("UPDATE knowledge_import_jobs SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",id);
            repository.recoverShared();var second=repository.claim(id).orElseThrow();
            assertThat(second.deadline()).isEqualTo(first.deadline());
            assertThat(second.token()).isNotEqualTo(first.token());
            repository.ready(first,"{}");repository.failed(first,"stale",false);
            assertThat(repository.renew(first)).isFalse();assertThat(status(id)).isEqualTo("RUNNING");
            repository.ready(second,"{}");assertThat(status(id)).isEqualTo("READY");
        }
        @Test void canceledExpiredAndDeletedOwnerJobsCannotBeRevived() throws Exception {
            String canceled=submit(),expired=submit();
            performDelete("/api/knowledge/imports/jobs/"+canceled,token);
            jdbc.update("UPDATE knowledge_import_jobs SET execution_deadline=CURRENT_TIMESTAMP(6) WHERE id=?",expired);
            repository.recoverShared();executor.execute(canceled);executor.execute(expired);
            assertThat(status(canceled)).isEqualTo("CANCELED");assertThat(status(expired)).isEqualTo("FAILED");
            String deleted=submit();jdbc.update("DELETE FROM app_users WHERE id=?",owner);
            assertThat(repository.claim(deleted)).isEmpty();
        }
        @Test void retryBudgetIsFiniteAndMissingFilesNeverProduceDrafts() throws Exception {
            String id=submit();Files.delete(shared.resolve(id).resolve("source.upload"));
            for(int attempt=0;attempt<3;attempt++) {
                executor.execute(id);
                jdbc.update("UPDATE knowledge_import_jobs SET next_attempt_at=CURRENT_TIMESTAMP(6) WHERE id=?",id);
            }
            assertThat(status(id)).isEqualTo("FAILED");assertThat(repository.claim(id)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT draft_json FROM knowledge_import_jobs WHERE id=?",String.class,id)).isNull();
        }
        @Test void fullAdmissionAndDelayedCleanupPreserveQueuedFilesAndUnknownArtifacts() throws Exception {
            String id=submit();submit();submit();
            assertThatThrownBy(()->repository.enqueue(owner,java.util.UUID.randomUUID().toString(),"x","x",180)).isInstanceOf(cn.edu.whu.zhizhida.common.BusinessException.class);
            Path folder=shared.resolve(id),unknown=folder.resolve("keep.txt");Files.writeString(unknown,"keep");
            Files.setLastModifiedTime(folder,java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(2000)));
            executor.maintain();assertThat(folder.resolve("source.upload")).exists();
            performDelete("/api/knowledge/imports/jobs/"+id,token);
            executor.maintain();assertThat(folder.resolve("source.upload")).doesNotExist();assertThat(unknown).exists();
        }
        @Test void rolledBackVersionNeverCreatesDispatchableIndexWork() {
            String id=java.util.UUID.randomUUID().toString();
            transactions.executeWithoutResult(tx->{
                documents.insert(owner,new cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument(id,"rollback","note.md",1,"body",null,java.util.List.of()));
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_index_jobs WHERE document_id=?",Integer.class,id)).isEqualTo(1);
                tx.setRollbackOnly();
            });
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_index_jobs WHERE document_id=?",Integer.class,id)).isZero();
        }
    }

    @Nested
    @EnabledIfEnvironmentVariable(named="RUN_MIDDLEWARE_TESTS",matches="true")
    @DirtiesContext
    @SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
            "knowledge.dispatch.mode=rabbitmq","app.run-dispatch.mode=rabbitmq","knowledge.dispatch.initial-delay-ms=600000",
            "knowledge.import.worker-enabled=false","agent.vector.enabled=true","agent.vector.worker-enabled=false",
            "app.outbox.publisher.enabled=false","app.run-recovery.enabled=false","interview.runs.worker-enabled=false",
            "spring.rabbitmq.publisher-confirm-type=correlated","spring.rabbitmq.publisher-returns=true","spring.rabbitmq.listener.simple.prefetch=1"})
    class Broker extends Imports {
        static final String namespace="zhizhida.test.knowledge."+UUID.randomUUID();
        @TempDir static Path shared;
        static String env(String k,String fallback) { return System.getenv().getOrDefault(k,fallback); }
        @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
            p.add("knowledge.import.shared-directory",()->shared.toString());
            p.add("knowledge.dispatch.namespace",()->namespace);p.add("app.run-dispatch.namespace",()->namespace+".run");
            p.add("spring.rabbitmq.host",()->env("RABBITMQ_HOST","127.0.0.1"));
            p.add("spring.rabbitmq.port",()->env("RABBITMQ_PORT","5672"));
            p.add("spring.rabbitmq.username",()->env("RABBITMQ_USERNAME","zhizhida"));
            p.add("spring.rabbitmq.password",()->env("RABBITMQ_PASSWORD","zhizhida-local"));
            p.add("spring.rabbitmq.virtual-host",()->env("RABBITMQ_VHOST","/zhizhida-lab"));
        }
        @Autowired KnowledgeTaskDispatch dispatch;
        @Autowired CachingConnectionFactory connection;
        @Autowired ApplicationContext application;
        @Autowired RabbitListenerEndpointRegistry listeners;
        @BeforeEach void setup() throws Exception {
            jdbc.update("DELETE FROM knowledge_index_jobs");jdbc.update("DELETE FROM knowledge_documents");
            when(vectors.fingerprint()).thenReturn(namespace);
            var admin=new RabbitAdmin(connection);admin.setApplicationContext(application);admin.initialize();
        }
        @Override @AfterEach void deleteOwner() throws Exception {
            listeners.getListenerContainer("knowledge-import").stop();listeners.getListenerContainer("knowledge-index").stop();
            super.deleteOwner();
            jdbc.update("DELETE FROM knowledge_index_jobs WHERE profile=?",namespace);
            var admin=new RabbitAdmin(connection);
            for(String q:List.of(".import.q",".index.q",".dead.q",".run.run.q",".run.dead.q")) admin.deleteQueue(namespace+q);
            for(String x:List.of(".x",".dlx",".run.x",".run.dlx")) admin.deleteExchange(namespace+x);
        }
        Message delivery(com.rabbitmq.client.Channel c,String kind,boolean redelivered) throws Exception {
            var r=c.basicGet(namespace+"."+kind+".q",false);assertThat(r).isNotNull();
            assertThat(r.getEnvelope().isRedeliver()).isEqualTo(redelivered);
            var props=new MessageProperties();props.setDeliveryTag(r.getEnvelope().getDeliveryTag());props.setMessageId(r.getProps().getMessageId());
            return new Message(r.getBody(),props);
        }
        @Test void commitBeforeAckIsRedeliveredWithoutParsingTwice() throws Exception {
            String id=submit();assertThat(repository.pending()).contains(id);dispatch.publish();
            try(var raw=connection.getRabbitConnectionFactory().newConnection();var c=raw.createChannel()) {
                var message=delivery(c,"import",false);
                var ackLost=(com.rabbitmq.client.Channel)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{com.rabbitmq.client.Channel.class},(proxy,method,args)->{
                    // 外层作用域关闭尚未确认的通道，触发真实消息代理重新投递。
                    if(method.getName().equals("basicAck")) throw new java.io.IOException("ack connection lost");
                    return method.invoke(c,args);
                });
                assertThatThrownBy(()->dispatch.importMessage(message,ackLost)).isInstanceOf(java.io.IOException.class);
            }
            try(var raw=connection.getRabbitConnectionFactory().newConnection();var c=raw.createChannel()) { dispatch.importMessage(delivery(c,"import",true),c); }
            assertThat(status(id)).isEqualTo("READY");
            assertThat(jdbc.queryForObject("SELECT attempts FROM knowledge_import_jobs WHERE id=?",Integer.class,id)).isEqualTo(1);
        }
        @Test void realListenerRecoversReservedNotificationAndCanceledJobsRemainCanceled() throws Exception {
            String id=submit();assertThat(repository.reserveNotice(id)).isTrue();
            // 模拟发布器领取持久通知后、发送前退出，再使通知到期以验证恢复。
            jdbc.update("UPDATE knowledge_import_jobs SET notice_after=CURRENT_TIMESTAMP(6) WHERE id=?",id);dispatch.publish();
            listeners.getListenerContainer("knowledge-import").start();
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while(!status(id).equals("READY") && System.nanoTime()<until) Thread.sleep(30);
            assertThat(status(id)).isEqualTo("READY");
            listeners.getListenerContainer("knowledge-import").stop();
            String canceled=submit();dispatch.publish();performDelete("/api/knowledge/imports/jobs/"+canceled,token);
            try(var raw=connection.getRabbitConnectionFactory().newConnection();var c=raw.createChannel()) { dispatch.importMessage(delivery(c,"import",false),c); }
            assertThat(status(canceled)).isEqualTo("CANCELED");
        }
        @Test void indexNotificationForDeletedDocumentOnlyRemovesVectors() throws Exception {
            var r=performPost("/api/knowledge/documents",Map.of("title","index","content","version one"),token);
            String id=objectMapper.readTree(r.getResponse().getContentAsString()).at("/data/id").asText();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_index_jobs WHERE document_id=? AND profile=?",Integer.class,id,namespace)).isEqualTo(1);
            dispatch.publish();performDelete("/api/knowledge/documents/"+id,token);
            try(var raw=connection.getRabbitConnectionFactory().newConnection();var c=raw.createChannel()) { dispatch.indexMessage(delivery(c,"index",false),c); }
            assertThat(jdbc.queryForObject("SELECT state FROM knowledge_index_jobs WHERE document_id=? AND profile=?",String.class,id,namespace)).isEqualTo("DELETED");
            verify(vectors,never()).index(any(),anyList());verify(vectors).remove(any(),eq(namespace));
        }
    }
}
