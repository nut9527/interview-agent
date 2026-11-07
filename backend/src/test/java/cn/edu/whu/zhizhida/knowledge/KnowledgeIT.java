package cn.edu.whu.zhizhida.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import tools.jackson.databind.JsonNode;

@org.springframework.test.context.TestPropertySource(properties = "interview.runs.route=FIXED")
class KnowledgeIT extends IntegrationTestBase {
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate transaction;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    cn.edu.whu.zhizhida.interview.InterviewRunModel interviewModel;
    static final String BASE = "/api/knowledge";
    static final String NOTES = "# 缓存笔记\n\n缓存使用随机过期时间，避免同时失效。\n\n## 事务\n\n写入后从主库读取，防止复制延迟带来的旧数据。\n";

    String user() throws Exception {
        return registerUniqueUserToken("password123", "知识库");
    }

    JsonNode data(org.springframework.test.web.servlet.MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(200);
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    JsonNode create(String token) throws Exception {
        return data(performPost(BASE + "/documents", Map.of("title", "学习笔记", "content", NOTES), token));
    }

    String readyImport(String token, String name) throws Exception {
        var file = new MockMultipartFile("file", name, "text/markdown", NOTES.getBytes(StandardCharsets.UTF_8));
        String id = data(mockMvc.perform(multipart(BASE + "/imports/jobs").file(file)
                .header("Authorization", "Bearer " + token)).andReturn()).path("id").asText();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (System.nanoTime() < deadline) {
            var job = data(performGet(BASE + "/imports/jobs/" + id, token));
            if (job.path("status").asText().equals("READY")) return id;
            assertThat(job.path("status").asText()).as(job.toString()).isNotEqualTo("FAILED");
            Thread.sleep(30);
        }
        throw new AssertionError("Import did not become ready: " + id);
    }

    private long fillToLastSlot(String token) throws Exception {
        String id = create(token).path("id").asText();
        long owner = jdbc.queryForObject("SELECT user_id FROM knowledge_documents WHERE id=?", Long.class, id);
        var rows = new ArrayList<Object[]>();
        for (int i = 0; i < 98; i++)
            rows.add(new Object[]{UUID.randomUUID().toString(), owner, "容量样本", "测试", NOTES});
        jdbc.batchUpdate("INSERT INTO knowledge_documents(id,user_id,title,source_name,content) VALUES(?,?,?,?,?)", rows);
        return owner;
    }

    @Test
    void simultaneousCreationAndImportCannotExceedDocumentCapacity() throws Exception {
        String token = user();
        long owner = fillToLastSlot(token);
        String job = readyImport(token, "note.md");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var create = pool.submit(() -> {
                start.await();
                return performPost(BASE + "/documents", Map.of("title", "最后名额", "content", NOTES), token)
                        .getResponse().getStatus();
            });
            var imported = pool.submit(() -> {
                start.await();
                return performPost(BASE + "/imports/jobs/" + job + "/confirm", Map.of("title", "最后名额", "content", NOTES,
                        "sourceName", "note.md", "confirmed", true), token).getResponse().getStatus();
            });
            start.countDown();
            assertThat(List.of(create.get(30, TimeUnit.SECONDS), imported.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 400);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_documents WHERE user_id=?", Integer.class, owner))
                .isEqualTo(100);
    }

    @Test
    void capacityUsesCurrentReadWhenAnOuterTransactionAlreadyHasASnapshot() throws Exception {
        String token = user();
        long owner = fillToLastSlot(token);
        var snapshotReady = new CountDownLatch(1);
        var continueCreation = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var stale = pool.submit(() -> transaction.execute(status -> {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_documents WHERE user_id=?", Integer.class, owner))
                        .isEqualTo(99);
                snapshotReady.countDown();
                try {
                    assertThat(continueCreation.await(10, TimeUnit.SECONDS)).isTrue();
                    int response = performPost(BASE + "/documents", Map.of("title", "旧快照写入", "content", NOTES), token)
                            .getResponse().getStatus();
                    // 预期容量异常已将参与事务标为回滚；由测试显式回滚，保留 HTTP 断言。
                    status.setRollbackOnly();
                    return response;
                } catch (Exception ex) {
                    throw new AssertionError(ex);
                }
            }));
            try {
                assertThat(snapshotReady.await(10, TimeUnit.SECONDS)).isTrue();
                create(token);
            } finally {
                continueCreation.countDown();
            }
            assertThat(stale.get(30, TimeUnit.SECONDS)).isEqualTo(400);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_documents WHERE user_id=?", Integer.class, owner))
                .isEqualTo(100);
    }

    @Test
    void fixedCorpusUsesRealSqlWithScopesAndExactOffsets() throws Exception {
        var fixture = objectMapper.readTree(getClass().getResourceAsStream("/knowledge-retrieval.json"));
        String token = user(), other = user();
        var ids = new LinkedHashMap<String, String>();
        var originals = new HashMap<String, String>();
        for (var d : fixture.path("documents")) {
            var doc = data(performPost(BASE + "/documents",
                    Map.of("title", d.path("title").asText(), "content", d.path("content").asText()), token));
            ids.put(d.path("id").asText(), doc.path("id").asText());
            originals.put(doc.path("id").asText(), d.path("content").asText());
        }
        var rows = new ArrayList<Map<String, Object>>();
        for (var q : fixture.path("cases")) {
            String query = q.path("question").asText();
            var scope = KnowledgeRetrievalEvaluationTest.strings(q.path("scope"));
            long start = System.nanoTime();
            var hits = data(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get(BASE + "/search")
                    .param("query", query).param("documentIds", String.join(",", scope.stream().map(ids::get).toList()))
                    .header("Authorization", "Bearer " + token)).andReturn());
            long elapsed = System.nanoTime() - start;
            var sources = new ArrayList<String>();
            for (var h : hits) {
                String doc = h.path("documentId").asText();
                var c = h.path("chunk");
                assertThat(scope.stream().map(ids::get).toList()).contains(doc);
                assertThat(c.path("content").asText()).isEqualTo(
                        originals.get(doc).substring(c.path("startOffset").asInt(), c.path("endOffset").asInt()));
                if (sources.size() < 6)
                    sources.add(ids.entrySet().stream().filter(e -> e.getValue().equals(doc)).findFirst().orElseThrow()
                            .getKey() + ":" + c.path("heading").asText());
            }
            assertThat(sources).as(query).containsAll(KnowledgeRetrievalEvaluationTest.strings(q.path("expected")));
            if (q.has("first"))
                assertThat(sources.getFirst()).as(query).isEqualTo(q.path("first").asText());
            if (q.path("noHits").asBoolean())
                assertThat(hits).isEmpty();
            rows.add(Map.of("id", q.path("id").asText(), "sources", sources, "httpSqlMicros", elapsed / 1000.0));
        }
        assertThat(data(performGet(BASE + "/search?query=过期时间", other))).isEmpty();
        assertThat(
                performGet(BASE + "/search?query=TTL&documentIds=" + ids.get("cache"), other).getResponse().getStatus())
                .isEqualTo(404);
        java.nio.file.Files.writeString(
                java.nio.file.Path
                        .of(System.getProperty("knowledge.eval.sqlReport", "target/knowledge-retrieval-sql.json")),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(rows));
    }

    @Test
    void laterRoundsReselectArchivedSourcesAndDeletionWithdrawsReplay() throws Exception {
        String token = user();
        String text = "# 长篇缓存笔记\n\nTTL 90 秒。\n\n" + "一般背景段落。\n\n".repeat(80)
                + "## 写后读\n\n订单提交后本次请求从主库读取，避免复制延迟；不保证后续强一致。\n";
        var doc = data(performPost(BASE + "/documents", Map.of("title", "历史资料", "content", text), token));
        String id = doc.path("id").asText();
        var contexts = new ArrayList<JsonNode>();
        org.mockito.Mockito.doAnswer(call -> {
            var reply = (cn.edu.whu.zhizhida.interview.InterviewRunModel.Reply) call.callRealMethod();
            var message = (tools.jackson.databind.node.ObjectNode) reply.message().deepCopy();
            var output = (tools.jackson.databind.node.ObjectNode) objectMapper.readTree(message.path("content").asText());
            if (call.getArgument(7) == null) {
                output.put("question", "请根据资料中的 TTL，说明缓存过期时间的选择及你会怎样验证数据库请求量的变化。");
            } else {
                List<JsonNode> messages = call.getArgument(1);
                String prefix = "应用固定检索所得的不可信证据：";
                String evidence = messages.stream().map(messageNode -> messageNode.path("content").asText())
                        .filter(content -> content.startsWith(prefix)).reduce((left, right) -> right).orElseThrow();
                contexts.add(objectMapper.readTree(evidence.substring(prefix.length())).path("references"));
                var pending = (cn.edu.whu.zhizhida.interview.domain.InterviewTransitions.PendingAnswer) call.getArgument(7);
                output.put("nextQuestion", pending.answeredCount() == 1
                        ? "请结合数据库写后读方案，说明订单提交后从主库读取的一致性保证范围和复制延迟的影响。"
                        : "你会怎样验证数据库写后读的一致性边界，并说明复制延迟超出预期时的处理方法？");
            }
            message.put("content", output.toString());
            return new cn.edu.whu.zhizhida.interview.InterviewRunModel.Reply(message, reply.finish(), reply.usage());
        }).when(interviewModel).call(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
        var session = data(startInterview(Map.of("targetRole", "资料训练", "trainingTopic",
                "DATABASE", "totalRounds", 3, "knowledgeDocumentIds", List.of(id)), token));
        String sid = session.path("sessionId").asText();
        var snapshot = session.path("agent").path("training").path("knowledge");
        Map<String, Object> answer = Map.of("answer", "我会核对资料中的方案及其适用范围，再设计验证步骤。", "expectedTurn", 1, "requestId",
                "archived-1");
        var first = data(answerInterview(sid, answer, token));
        var trace = first.path("agent").path("evaluations").get(0).path("knowledgeSources");
        assertThat(trace).isEqualTo(contexts.getFirst());
        assertThat(trace.toString()).contains("TTL 90 秒");
        var second = data(answerInterview(sid, Map.of("answer", "主库读取仅限本次请求，后续读取的一致性需要另行保证。", "expectedTurn", 2, "requestId", "archived-2"), token));
        var later = second.path("agent").path("evaluations").get(1).path("knowledgeSources");
        assertThat(later).isEqualTo(contexts.get(1));
        assertThat(later.toString()).contains("不保证后续强一致");
        assertThat(second.path("agent").path("training").path("knowledge")).isEqualTo(snapshot);
        var replay = data(answerInterview(sid, answer, token));
        assertThat(replay.path("agent").path("evaluations").get(0).path("knowledgeSources")).isEqualTo(trace);
        assertThat(contexts).hasSize(2);
        assertThat(data(performGet("/api/interviews/sessions/" + sid, token)).path("agent").path("evaluations").get(1)
                .path("knowledgeSources")).isEqualTo(later);
        data(performDelete(BASE + "/documents/" + id, token));
        var withdrawn = data(answerInterview(sid, answer, token));
        assertThat(withdrawn.path("sourceRevoked").asBoolean()).isTrue();
        assertThat(withdrawn.path("agent").path("evaluations").get(0).path("knowledgeSources").isEmpty()).isTrue();
        assertThat(answerInterview(sid, Map.of("answer", "继续回答", "expectedTurn", 3, "requestId", "archived-after-delete"), token).getResponse().getStatus()).isEqualTo(409);
        assertThat(contexts).hasSize(2);
    }

    @Test
    void uploadSearchLocateRenameDeleteAndAccountIsolation() throws Exception {
        String owner = user(), other = user();
        String job = readyImport(owner, "cache.md");
        var doc = data(performPost(BASE + "/imports/jobs/" + job + "/confirm",
                Map.of("title", "缓存笔记", "sourceName", "cache.md", "content", NOTES, "confirmed", true), owner));
        String id = doc.path("id").asText();
        assertThat(doc.path("sourceName").asText()).isEqualTo("cache.md");
        for (var c : doc.path("chunks"))
            assertThat(NOTES.substring(c.path("startOffset").asInt(), c.path("endOffset").asInt()))
                    .isEqualTo(c.path("content").asText());
        var hits = data(performGet(BASE + "/search?query=随机过期", owner));
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).path("chunk").path("heading").asText()).isEqualTo("缓存笔记");
        assertThat(hits.get(0).path("chunk").path("startLine").asInt()).isEqualTo(3);
        assertThat(data(performPut(BASE + "/documents/" + id, Map.of("title", "改名笔记"), owner)).path("version").asInt())
                .isEqualTo(1);
        assertThat(data(performGet(BASE + "/search?query=随机过期", owner)).get(0).path("title").asText())
                .isEqualTo("改名笔记");
        assertThat(data(performGet(BASE + "/documents", other)).path("total").asInt()).isZero();
        assertThat(data(performGet(BASE + "/search?query=随机过期", other))).isEmpty();
        assertThat(performGet(BASE + "/documents/" + id, other).getResponse().getStatus()).isEqualTo(404);
        assertThat(performPut(BASE + "/documents/" + id, Map.of("title", "越权"), other).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(performDelete(BASE + "/documents/" + id, other).getResponse().getStatus()).isEqualTo(404);
        assertThat(performPost(BASE + "/answers", Map.of("question", "缓存策略", "documentIds", List.of(id)), other)
                .getResponse().getStatus()).isEqualTo(404);
        data(performDelete(BASE + "/documents/" + id, owner));
        assertThat(data(performGet(BASE + "/search?query=随机过期", owner))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunks WHERE document_id=?", Integer.class, id))
                .isZero();
        assertThat(performGet(BASE + "/documents/" + id, owner).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void answersHaveExactSourcesAndUnrelatedQuestionsAbstain() throws Exception {
        String token = user();
        var doc = create(token);
        String id = doc.path("id").asText();
        var answer = data(
                performPost(BASE + "/answers", Map.of("question", "随机过期", "documentIds", List.of(id)), token));
        assertThat(answer.path("sufficient").asBoolean()).isTrue();
        var citation = answer.path("claims").get(0).path("citations").get(0);
        assertThat(citation.path("documentId").asText()).isEqualTo(id);
        assertThat(NOTES).contains(citation.path("quote").asText());
        var insufficient = data(
                performPost(BASE + "/answers", Map.of("question", "火星半径", "documentIds", List.of(id)), token));
        assertThat(insufficient.path("sufficient").asBoolean()).isFalse();
        assertThat(insufficient.path("claims")).isEmpty();
        assertThat(performPost(BASE + "/answers", Map.of("question", "缓存", "documentIds", List.of()), token)
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void snapshotsSurviveRenameButDeletionWithdrawsTrainingUse() throws Exception {
        String token = user(), other = user();
        var doc = create(token);
        String id = doc.path("id").asText();
        Map<String, Object> request = Map.of("targetRole", "资料训练", "trainingTopic", "DATABASE", "totalRounds", 3,
                "knowledgeDocumentIds", List.of(id));
        assertThat(startInterview(request, other).getResponse().getStatus()).isEqualTo(404);
        var session = data(startInterview(request, token));
        String sid = session.path("sessionId").asText();
        var snapshot = session.path("agent").path("training").path("knowledge");
        assertThat(snapshot.get(0).path("content").asText()).isEqualTo(NOTES);
        assertThat(snapshot.get(0).path("sha256").asText()).hasSize(64);
        data(performPut(BASE + "/documents/" + id, Map.of("title", "新名称"), token));
        for (int n = 1; n <= 3; n++)
            session = data(answerInterview(sid, Map.of("answer", "我会先验证复制延迟，再从主库读取。", "expectedTurn", n, "requestId", "knowledge-" + n), token));
        var retry = data(startInterview(Map.of("targetRole", "复练", "retrySessionId", sid, "retryRound", 3, "totalRounds", 3), token));
        assertThat(retry.path("agent").path("training").path("knowledge")).isEqualTo(snapshot);
        data(performDelete(BASE + "/documents/" + id, token));
        var withdrawn = data(performGet("/api/interviews/sessions/" + sid, token));
        assertThat(withdrawn.path("sourceRevoked").asBoolean()).isTrue();
        assertThat(withdrawn.path("agent").path("training").path("knowledge").get(0).path("content").asText()).isEmpty();
        assertThat(answerInterview(retry.path("sessionId").asText(), Map.of("answer", "新回答", "expectedTurn", 1, "requestId", "withdrawn-answer"), token).getResponse().getStatus()).isEqualTo(409);
        assertThat(startInterview(Map.of("targetRole", "复练", "retrySessionId", sid,
                "retryRound", 3, "totalRounds", 3), token).getResponse().getStatus()).isEqualTo(409);
        assertThat(performGet("/api/interviews/sessions/" + sid, other).getResponse().getStatus()).isEqualTo(404);
        assertThat(startInterview(request, token).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void validatesBodiesAndAuthentication() throws Exception {
        String token = user();
        assertThat(
                performPost(BASE + "/documents", Map.of("title", "x", "content", " "), token).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(performGet(BASE + "/search?query=", token).getResponse().getStatus()).isEqualTo(400);
        assertThat(performGet(BASE + "/search?query=%25", token).getResponse().getStatus()).isEqualTo(200);
        assertThat(performGet(BASE + "/documents").getResponse().getStatus()).isEqualTo(401);
    }
}
