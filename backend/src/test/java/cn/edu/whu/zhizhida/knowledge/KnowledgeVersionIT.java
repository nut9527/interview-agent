package cn.edu.whu.zhizhida.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import tools.jackson.databind.JsonNode;

class KnowledgeVersionIT extends IntegrationTestBase {
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    javax.sql.DataSource dataSource;
    static final String BASE = "/api/knowledge/documents/";
    static final String V1 = "# 缓存实验\r\n\r\n缓存 TTL 90 秒，旧标记 amber。🙂\r\n";
    static final String V2 = "# 缓存实验\n\n缓存 TTL 180 秒，新标记 cobalt。🙂\n";

    String user() throws Exception {
        return registerUniqueUserToken("password123", "版本验收");
    }

    JsonNode data(org.springframework.test.web.servlet.MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(200);
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    @Test
    void migrationArchivesExistingOriginalsWithoutChangingLegacyChunks() throws Exception {
        // 通过当前连接内的临时表隔离升级数据，避免影响其他测试。
        try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
            try {
                for (String table : List.of("knowledge_documents", "knowledge_chunks")) {
                    sql.execute("CREATE TEMPORARY TABLE version_migration_copy LIKE " + table);
                    sql.execute("CREATE TEMPORARY TABLE " + table + " LIKE version_migration_copy");
                    sql.execute("DROP TEMPORARY TABLE version_migration_copy");
                }
                sql.execute(
                        "ALTER TABLE knowledge_chunks DROP INDEX idx_knowledge_position,DROP COLUMN version,ADD UNIQUE KEY idx_knowledge_position(document_id,ordinal)");
                try (var insert = connection.prepareStatement(
                        "INSERT INTO knowledge_documents(id,user_id,title,source_name,content) VALUES('legacy',1,'旧资料','old.md',?)")) {
                    insert.setString(1, V1);
                    insert.executeUpdate();
                }
                sql.execute(
                        "INSERT INTO knowledge_chunks(id,document_id,ordinal,heading,start_offset,end_offset,start_line,end_line,content) VALUES('legacy:1','legacy',1,'旧标题',0,5,1,1,'旧片段')");
                String migration = new org.springframework.core.io.ClassPathResource(
                        "db/migration/V28__knowledge_versions.sql").getContentAsString(StandardCharsets.UTF_8)
                        .replace("CREATE TABLE knowledge_versions", "CREATE TEMPORARY TABLE knowledge_versions")
                        .replace("PRIMARY KEY (document_id, version),", "PRIMARY KEY (document_id, version)")
                        .replace(
                                "CONSTRAINT fk_knowledge_version FOREIGN KEY (document_id) REFERENCES knowledge_documents(id) ON DELETE CASCADE",
                                "");
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                        new org.springframework.core.io.ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8)));
                try (var rows = sql
                        .executeQuery("SELECT version,content FROM knowledge_versions WHERE document_id='legacy'")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isEqualTo(1);
                    assertThat(rows.getString(2)).isEqualTo(V1);
                }
                try (var rows = sql.executeQuery(
                        "SELECT id,version,start_offset,end_offset,content FROM knowledge_chunks WHERE document_id='legacy'")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("legacy:1");
                    assertThat(rows.getInt(2)).isEqualTo(1);
                    assertThat(rows.getInt(3)).isZero();
                    assertThat(rows.getInt(4)).isEqualTo(5);
                    assertThat(rows.getString(5)).isEqualTo("旧片段");
                }
            } finally {
                for (String table : List.of("knowledge_versions", "knowledge_chunks", "knowledge_documents",
                        "version_migration_copy"))
                    sql.execute("DROP TEMPORARY TABLE IF EXISTS " + table);
            }
        }
    }

    @Test
    void createAskTrainEditSearchAndVerifyOldCitationAndSnapshotThenRestore() throws Exception {
        String token = user();
        var original = data(performPost("/api/knowledge/documents", Map.of("title", "versions.md", "content", V1), token));
        String id = original.path("id").asText();
        var answer = data(
                performPost("/api/knowledge/answers", Map.of("question", "TTL", "documentIds", List.of(id)), token));
        var citation = answer.path("claims").get(0).path("citations").get(0);
        assertThat(citation.path("version").asInt()).isEqualTo(1);
        var session = data(startInterview(Map.of("targetRole", "版本训练", "trainingTopic",
                "DATABASE", "totalRounds", 3, "knowledgeDocumentIds", List.of(id)), token));
        String sid = session.path("sessionId").asText();
        var snapshot = session.path("agent").path("training").path("knowledge");
        var updated = data(performPut(BASE + id + "/content", Map.of("content", V2, "expectedVersion", 1), token));
        assertThat(updated.path("id").asText()).isEqualTo(id);
        assertThat(updated.path("version").asInt()).isEqualTo(2);
        assertThat(updated.path("sourceName")).isEqualTo(original.path("sourceName"));
        assertThat(data(performGet("/api/knowledge/search?query=amber", token))).isEmpty();
        var hits = data(performGet("/api/knowledge/search?query=cobalt", token));
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).path("version").asInt()).isEqualTo(2);
        for (var chunk : updated.path("chunks")) {
            assertThat(chunk.path("content").asText())
                    .isEqualTo(V2.substring(chunk.path("startOffset").asInt(), chunk.path("endOffset").asInt()));
            assertThat(chunk.path("id").asText()).contains(":v2:");
        }
        var old = data(performGet(BASE + id + "/versions/" + citation.path("version").asInt(), token));
        assertThat(old.path("content").asText()).isEqualTo(V1);
        assertThat(old.path("chunks")).isEqualTo(original.path("chunks"));
        var quoted = java.util.stream.StreamSupport.stream(old.path("chunks").spliterator(), false)
                .filter(c -> c.path("id").asText().equals(citation.path("chunkId").asText())).findFirst().orElseThrow();
        assertThat(quoted.path("content").asText()).contains(citation.path("quote").asText());
        assertThat(quoted.path("startLine")).isEqualTo(citation.path("startLine"));
        var continued = data(answerInterview(sid, Map.of("answer", "我会核对缓存 TTL 并测量数据库请求量。", "expectedTurn", 1, "requestId", "version-v1"), token));
        assertThat(continued.path("agent").path("training").path("knowledge")).isEqualTo(snapshot);
        assertThat(snapshot.get(0).path("content").asText()).isEqualTo(V1);
        assertThat(data(performGet("/api/interviews/sessions/" + sid, token)).path("agent").path("training")
                .path("knowledge")).isEqualTo(snapshot);
        var newSession = data(startInterview(Map.of("targetRole", "新版训练", "trainingTopic",
                "DATABASE", "totalRounds", 3, "knowledgeDocumentIds", List.of(id)), token));
        assertThat(newSession.path("agent").path("training").path("knowledge").get(0).path("version").asInt())
                .isEqualTo(2);
        assertThat(performPost(BASE + id + "/versions/1/restore", Map.of("expectedVersion", 1), token).getResponse()
                .getStatus()).isEqualTo(409);
        var restored = data(performPost(BASE + id + "/versions/1/restore", Map.of("expectedVersion", 2), token));
        assertThat(restored.path("version").asInt()).isEqualTo(3);
        assertThat(restored.path("content").asText()).isEqualTo(V1);
        assertThat(data(performGet(BASE + id + "/versions/2", token)).path("content").asText()).isEqualTo(V2);
        var versions = data(performGet(BASE + id + "/versions?size=2", token));
        assertThat(versions.path("total").asInt()).isEqualTo(3);
        assertThat(versions.path("items")).hasSize(2);
        assertThat(versions.path("items").get(0).path("version").asInt()).isEqualTo(3);
        assertThat(data(performGet(BASE + id + "/versions?page=2&size=2", token)).path("items").get(0).path("version")
                .asInt()).isEqualTo(1);
    }

    @Test
    void simultaneousSavesAllowExactlyOneWriterAndKeepCompleteHistory() throws Exception {
        String token = user();
        var doc = data(performPost("/api/knowledge/documents", Map.of("title", "并发", "content", V1), token));
        String id = doc.path("id").asText();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<Integer>>();
            for (String text : List.of(V2, "竞争正文 winner"))
                futures.add(pool.submit(() -> {
                    start.await();
                    return performPut(BASE + id + "/content", Map.of("content", text, "expectedVersion", 1), token)
                            .getResponse().getStatus();
                }));
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var future : futures)
                statuses.add(future.get(30, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        }
        var current = data(performGet(BASE + id, token));
        assertThat(current.path("version").asInt()).isEqualTo(2);
        assertThat(data(performGet(BASE + id + "/versions/2", token)).path("content"))
                .isEqualTo(current.path("content"));
        assertThat(data(performGet(BASE + id + "/versions", token)).path("total").asInt()).isEqualTo(2);
        assertThat(data(performGet(BASE + id + "/versions/1", token)).path("content").asText()).isEqualTo(V1);
    }

    @Test
    void validatesOwnershipVersionsAndBodiesAndCascadesAllArchives() throws Exception {
        String token = user(), other = user();
        var doc = data(performPost("/api/knowledge/documents", Map.of("title", "私有", "content", V1), token));
        String id = doc.path("id").asText();
        assertThat(performGet(BASE + java.util.UUID.randomUUID() + "/versions", token).getResponse().getStatus()).isEqualTo(404);
        for (String suffix : List.of("/versions", "/versions/1"))
            assertThat(performGet(BASE + id + suffix, other).getResponse().getStatus()).isEqualTo(404);
        assertThat(performPut(BASE + id + "/content", Map.of("content", V2, "expectedVersion", 1), other).getResponse()
                .getStatus()).isEqualTo(404);
        assertThat(performPost(BASE + id + "/versions/1/restore", Map.of("expectedVersion", 1), other).getResponse()
                .getStatus()).isEqualTo(404);
        for (var body : List.of(Map.of("content", V2), Map.of("content", " ", "expectedVersion", 1),
                Map.of("content", V2, "expectedVersion", 0), Map.of("content", "bad\0body", "expectedVersion", 1)))
            assertThat(performPut(BASE + id + "/content", body, token).getResponse().getStatus()).isEqualTo(400);
        assertThat(performGet(BASE + id + "/versions/99", token).getResponse().getStatus()).isEqualTo(404);
        assertThat(performPost(BASE + id + "/versions/99/restore", Map.of("expectedVersion", 1), token).getResponse()
                .getStatus()).isEqualTo(404);
        data(performPut(BASE + id + "/content", Map.of("content", V2, "expectedVersion", 1), token));
        data(performDelete(BASE + id, token));
        for (String table : List.of("knowledge_versions", "knowledge_chunks"))
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE document_id=?", Integer.class, id))
                    .isZero();
        assertThat(performGet(BASE + id + "/versions/1", token).getResponse().getStatus()).isEqualTo(404);
    }
}
