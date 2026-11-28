package cn.edu.whu.zhizhida.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import cn.edu.whu.zhizhida.ai.api.KnowledgeVectors;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "agent.vector.enabled=true",
            "agent.vector.worker-enabled=false",
            "interview.runs.worker-enabled=false"
        })
class KnowledgeIndexLifecycleIT extends IntegrationTestBase {
    @Autowired KnowledgeIndexWorker worker;
    @Autowired KnowledgeIndexRepository jobs;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean KnowledgeVectors vectors;
    String token, id;

    @BeforeEach
    void prepare() throws Exception {
        // 本测试仅使用可丢弃的集成测试数据库。
        jdbc.update("DELETE FROM knowledge_index_jobs");
        jdbc.update("DELETE FROM knowledge_documents");
        when(vectors.fingerprint()).thenReturn("fixture-v1");
        token = registerUniqueUserToken("password123", "索引验收");
        id =
                objectMapper
                        .readTree(
                                performPost(
                                                "/api/knowledge/documents",
                                                Map.of(
                                                        "title",
                                                        "cache",
                                                        "content",
                                                        "cache consistency"),
                                                token)
                                        .getResponse()
                                        .getContentAsString())
                        .at("/data/id")
                        .asString();
        assertThat(id).isNotBlank();
    }

    String state() {
        return jdbc.queryForObject(
                "SELECT state FROM knowledge_index_jobs WHERE document_id=? ORDER BY id DESC LIMIT 1",
                String.class,
                id);
    }

    @Test
    void questionsAndFocusedQuizzesUseSemanticOnlyHitsFromOwnedArchive() throws Exception {
        String query = "事务提交后的可见性";
        String chunk =
                jdbc.queryForObject(
                        "SELECT id FROM knowledge_chunks WHERE document_id=? AND version=1 ORDER BY ordinal LIMIT 1",
                        String.class,
                        id);
        var scope =
                new KnowledgeVectors.Scope(
                        id, 1, cn.edu.whu.zhizhida.common.Sha256.hex("cache consistency"));
        when(vectors.search(java.util.List.of(scope), query, 30))
                .thenReturn(java.util.List.of(new KnowledgeVectors.Match(scope, chunk)));

        var response =
                performPost(
                        "/api/knowledge/answers",
                        Map.of("question", query, "documentIds", java.util.List.of(id)),
                        token);
        assertThat(response.getResponse().getStatus()).isEqualTo(200);
        var answer =
                objectMapper.readTree(response.getResponse().getContentAsString()).path("data");
        assertThat(answer.path("sufficient").asBoolean()).isTrue();
        assertThat(answer.at("/claims/0/citations/0/chunkId").asString()).isEqualTo(chunk);

        var selection = Map.of("documentId", id, "version", 1, "chunkIds", java.util.List.of());
        var quizResponse =
                performPost(
                        "/api/knowledge/self-tests/generate",
                        Map.of("sources", java.util.List.of(selection), "count", 1, "focus", query),
                        token);
        assertThat(quizResponse.getResponse().getStatus()).isEqualTo(200);
        var quizzes =
                objectMapper.readTree(quizResponse.getResponse().getContentAsString()).path("data");
        assertThat(quizzes).hasSize(1);
        assertThat(quizzes.at("/0/question/citations/0/chunkId").asString()).isEqualTo(chunk);
        verify(vectors, times(2)).search(java.util.List.of(scope), query, 30);
    }

    @Test
    void discoversVersionsRebuildsAndRejectsOtherOwners() throws Exception {
        assertThat(worker.runOnce()).isTrue();
        assertThat(state()).isEqualTo("READY");
        assertThat(worker.runOnce()).isFalse();
        verify(vectors, times(1)).index(any(), anyList());
        assertThat(
                        performPost(
                                        "/api/knowledge/documents/" + id + "/index/rebuild",
                                        Map.of(),
                                        token)
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
        worker.runOnce();
        verify(vectors, times(2)).index(any(), anyList());
        String other = registerUniqueUserToken("password123", "other");
        assertThat(
                        performPost(
                                        "/api/knowledge/documents/" + id + "/index/rebuild",
                                        Map.of(),
                                        other)
                                .getResponse()
                                .getStatus())
                .isEqualTo(404);
        performPut(
                "/api/knowledge/documents/" + id + "/content",
                Map.of("content", "new cache version", "expectedVersion", 1),
                token);
        worker.runOnce();
        assertThat(
                        jdbc.queryForList(
                                "SELECT version FROM knowledge_index_jobs WHERE state='READY' ORDER BY version",
                                Integer.class))
                .containsExactly(1, 2);
        assertThat(
                        performGet("/api/knowledge/documents/" + id + "/index", token)
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
    }

    @Test
    void retriesFailureAndRecoversPublishedButUnacknowledgedWork() {
        doThrow(new IllegalStateException("sensitive upstream text"))
                .doNothing()
                .when(vectors)
                .index(any(), anyList());
        worker.runOnce();
        assertThat(state()).isEqualTo("RETRY");
        assertThat(worker.runOnce()).isFalse();
        jdbc.update("UPDATE knowledge_index_jobs SET next_at=CURRENT_TIMESTAMP(6)");
        worker.runOnce();
        assertThat(state()).isEqualTo("READY");
        jdbc.update("UPDATE knowledge_index_jobs SET state='RUNNING'");
        worker.runOnce();
        assertThat(state()).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT attempts FROM knowledge_index_jobs", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void accountCascadeLeavesCleanupTombstoneAndDeletionRetries() throws Exception {
        worker.runOnce();
        performDeleteWithBody("/api/users/me", Map.of("currentPassword", "password123"), token);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM knowledge_index_jobs WHERE source_id IS NULL",
                                Integer.class))
                .isEqualTo(1);
        doThrow(new IllegalStateException()).doNothing().when(vectors).remove(any(), anyString());
        worker.runOnce();
        assertThat(state()).isEqualTo("RETRY");
        jdbc.update("UPDATE knowledge_index_jobs SET next_at=CURRENT_TIMESTAMP(6)");
        worker.runOnce();
        assertThat(state()).isEqualTo("DELETED");
        verify(vectors, times(2)).remove(any(), eq("fixture-v1"));
        assertThat(worker.runOnce()).isFalse();
    }

    @Test
    void retiresOldProfileOnlyAfterNewGenerationIsReady() {
        worker.runOnce();
        when(vectors.fingerprint()).thenReturn("fixture-v2");
        worker.runOnce();
        verify(vectors, never()).remove(any(), anyString());
        worker.runOnce();
        verify(vectors).remove(any(), eq("fixture-v1"));
        assertThat(
                        jdbc.queryForList(
                                "SELECT state FROM knowledge_index_jobs ORDER BY id", String.class))
                .containsExactly("RETIRED", "READY");
        when(vectors.fingerprint()).thenReturn("fixture-v1");
        worker.runOnce();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM knowledge_index_jobs WHERE profile='fixture-v1'",
                                String.class))
                .isEqualTo("READY");
    }

    @Test
    void concurrentWorkersCannotEnterSameGenerationAndDeletionDuringWriteIsCleaned()
            throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            entered.countDown();
                            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                            return null;
                        })
                .when(vectors)
                .index(any(), anyList());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(worker::runOnce);
            Future<Boolean> notification;
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(worker.runOnce()).isFalse();
                jdbc.update("DELETE FROM knowledge_documents WHERE id=?", id);
                long jobId =
                        jdbc.queryForObject(
                                "SELECT id FROM knowledge_index_jobs WHERE document_id=?",
                                Long.class,
                                id);
                notification = executor.submit(() -> worker.execute(jobId));
                assertThatThrownBy(() -> notification.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(notification.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(state()).isEqualTo("DELETED");
        verify(vectors, times(1)).index(any(), anyList());
        verify(vectors, atLeastOnce()).remove(any(), eq("fixture-v1"));
    }
}
