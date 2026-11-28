package cn.edu.whu.zhizhida.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import cn.edu.whu.zhizhida.ai.api.KnowledgeVectors;
import cn.edu.whu.zhizhida.messaging.RocketTransport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.StaticSessionCredentialsProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class KnowledgeMqIT {
    abstract static class Imports extends IntegrationTestBase {
        @Autowired KnowledgeImportRepository repository;
        @Autowired JdbcTemplate jdbc;
        @MockitoBean KnowledgeVectors vectors;
        String token;
        long owner;

        @BeforeEach
        void registerOwner() throws Exception {
            token = registerUniqueUserToken("password123", "持久导入");
            owner =
                    objectMapper
                            .readTree(
                                    performGet("/api/users/me", token)
                                            .getResponse()
                                            .getContentAsString())
                            .at("/data/id")
                            .asLong();
        }

        @AfterEach
        void deleteOwner() throws Exception {
            performDeleteWithBody("/api/users/me", Map.of("currentPassword", "password123"), token);
        }

        String submit() throws Exception {
            var response =
                    mockMvc.perform(
                                    multipart("/api/knowledge/imports/jobs")
                                            .file(
                                                    new MockMultipartFile(
                                                            "file",
                                                            "note.md",
                                                            "text/plain",
                                                            "持久化资料正文"
                                                                    .getBytes(
                                                                            java.nio.charset
                                                                                    .StandardCharsets
                                                                                    .UTF_8)))
                                            .header("Authorization", "Bearer " + token))
                            .andReturn();
            assertThat(response.getResponse().getStatus())
                    .as(response.getResponse().getContentAsString())
                    .isEqualTo(200);
            return objectMapper
                    .readTree(response.getResponse().getContentAsString())
                    .at("/data/id")
                    .asString();
        }

        String status(String id) {
            return jdbc.queryForObject(
                    "SELECT status FROM knowledge_import_jobs WHERE id=?", String.class, id);
        }
    }

    @Nested
    @DirtiesContext
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                "knowledge.dispatch.mode=rocketmq",
                "app.run-dispatch.mode=rocketmq",
                "knowledge.dispatch.publisher-enabled=false",
                "knowledge.import.worker-enabled=false",
                "agent.vector.enabled=true",
                "agent.vector.worker-enabled=false",
                "app.outbox.publisher.enabled=false",
                "app.run-recovery.enabled=false",
                "interview.runs.worker-enabled=false"
            })
    class SharedFiles extends Imports {
        @TempDir static Path shared;

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry p) {
            p.add("knowledge.import.shared-directory", () -> shared.toString());
        }

        @MockitoBean RocketTransport transport;
        @Autowired KnowledgeRepository documents;
        @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
        @Autowired KnowledgeImportExecutor executor;

        @BeforeEach
        void configureVectors() {
            when(vectors.fingerprint()).thenReturn("durable-import-fixture");
        }

        @Test
        void switchingToMqReleasesAbandonedLocalUploadsAndRetainsUsableDrafts() throws Exception {
            String abandoned = java.util.UUID.randomUUID().toString();
            String active = java.util.UUID.randomUUID().toString();
            String draft = java.util.UUID.randomUUID().toString();
            for (String id : java.util.List.of(abandoned, active, draft)) {
                repository.insert(owner, id, "local.md", java.time.Instant.now().plusSeconds(600));
            }
            jdbc.update(
                    "UPDATE knowledge_import_jobs SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
                    abandoned);
            jdbc.update(
                    "UPDATE knowledge_import_jobs SET status='READY',draft_json='{}',lease_until=NULL WHERE id=?",
                    draft);

            repository.recoverShared();
            assertThat(status(abandoned)).isEqualTo("FAILED");
            assertThat(status(active)).isEqualTo("QUEUED");
            assertThat(status(draft)).isEqualTo("READY");
            String queued = submit(); // 废弃的本地任务已释放第三个上传名额。
            assertThat(repository.pending())
                    .contains(queued)
                    .doesNotContain(active, abandoned, draft);

            jdbc.update(
                    "UPDATE knowledge_import_jobs SET expires_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                    draft);
            repository.recoverShared();
            assertThat(repository.find(owner, draft, false)).isEmpty();
        }

        @Test
        void survivesApiExecutorReplacementAndRequiresIdempotentUserConfirmation()
                throws Exception {
            String id = submit();
            assertThat(status(id)).isEqualTo("QUEUED");
            assertThat(shared.resolve(id).resolve("source.upload")).exists();
            repository.recover();
            assertThat(status(id)).isEqualTo("QUEUED");
            executor.execute(id);
            executor.execute(id);
            assertThat(status(id)).isEqualTo("READY");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT attempts FROM knowledge_import_jobs WHERE id=?",
                                    Integer.class,
                                    id))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM knowledge_documents WHERE user_id=?",
                                    Integer.class,
                                    owner))
                    .isZero();
            var body =
                    Map.of(
                            "title",
                            "核对后资料",
                            "sourceName",
                            "note.md",
                            "content",
                            "修订正文",
                            "confirmed",
                            true);
            var first = performPost("/api/knowledge/imports/jobs/" + id + "/confirm", body, token);
            var second = performPost("/api/knowledge/imports/jobs/" + id + "/confirm", body, token);
            assertThat(first.getResponse().getStatus()).isEqualTo(200);
            assertThat(
                            objectMapper
                                    .readTree(first.getResponse().getContentAsString())
                                    .at("/data/id"))
                    .isEqualTo(
                            objectMapper
                                    .readTree(second.getResponse().getContentAsString())
                                    .at("/data/id"));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM knowledge_documents WHERE user_id=?",
                                    Integer.class,
                                    owner))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM knowledge_index_jobs j JOIN knowledge_documents d ON d.id=j.source_id WHERE d.user_id=? AND j.state='QUEUED'",
                                    Integer.class,
                                    owner))
                    .isEqualTo(1);
        }

        @Test
        void expiredLeaseRetriesWithNewTokenAndOldExecutorCannotCommitOrFailIt() throws Exception {
            String id = submit();
            var first = repository.claim(id).orElseThrow();
            assertThat(repository.claim(id)).isEmpty();
            jdbc.update(
                    "UPDATE knowledge_import_jobs SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
                    id);
            repository.recoverShared();
            var second = repository.claim(id).orElseThrow();
            assertThat(second.deadline()).isEqualTo(first.deadline());
            assertThat(second.token()).isNotEqualTo(first.token());
            repository.ready(first, "{}");
            repository.failed(first, "stale", false);
            assertThat(repository.renew(first)).isFalse();
            assertThat(status(id)).isEqualTo("RUNNING");
            repository.ready(second, "{}");
            assertThat(status(id)).isEqualTo("READY");
        }

        @Test
        void canceledExpiredAndDeletedOwnerJobsCannotBeRevived() throws Exception {
            String canceled = submit(), expired = submit();
            performDelete("/api/knowledge/imports/jobs/" + canceled, token);
            jdbc.update(
                    "UPDATE knowledge_import_jobs SET execution_deadline=CURRENT_TIMESTAMP(6) WHERE id=?",
                    expired);
            repository.recoverShared();
            executor.execute(canceled);
            executor.execute(expired);
            assertThat(status(canceled)).isEqualTo("CANCELED");
            assertThat(status(expired)).isEqualTo("FAILED");
            String deleted = submit();
            jdbc.update("DELETE FROM app_users WHERE id=?", owner);
            assertThat(repository.claim(deleted)).isEmpty();
        }

        @Test
        void retryBudgetIsFiniteAndMissingFilesNeverProduceDrafts() throws Exception {
            String id = submit();
            Files.delete(shared.resolve(id).resolve("source.upload"));
            for (int attempt = 0; attempt < 3; attempt++) {
                executor.execute(id);
                jdbc.update(
                        "UPDATE knowledge_import_jobs SET next_attempt_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                        id);
            }
            assertThat(status(id)).isEqualTo("FAILED");
            assertThat(repository.claim(id)).isEmpty();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT draft_json FROM knowledge_import_jobs WHERE id=?",
                                    String.class,
                                    id))
                    .isNull();
        }

        @Test
        void fullAdmissionAndDelayedCleanupPreserveQueuedFilesAndUnknownArtifacts()
                throws Exception {
            String id = submit();
            submit();
            submit();
            assertThatThrownBy(
                            () ->
                                    repository.enqueue(
                                            owner,
                                            java.util.UUID.randomUUID().toString(),
                                            "x",
                                            "x",
                                            180))
                    .isInstanceOf(cn.edu.whu.zhizhida.common.BusinessException.class);
            Path folder = shared.resolve(id), unknown = folder.resolve("keep.txt");
            Files.writeString(unknown, "keep");
            Files.setLastModifiedTime(
                    folder,
                    java.nio.file.attribute.FileTime.from(
                            java.time.Instant.now().minusSeconds(2000)));
            executor.maintain();
            assertThat(folder.resolve("source.upload")).exists();
            performDelete("/api/knowledge/imports/jobs/" + id, token);
            executor.maintain();
            assertThat(folder.resolve("source.upload")).doesNotExist();
            assertThat(unknown).exists();
        }

        @Test
        void rolledBackVersionNeverCreatesDispatchableIndexWork() {
            String id = java.util.UUID.randomUUID().toString();
            transactions.executeWithoutResult(
                    tx -> {
                        documents.insert(
                                owner,
                                new cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument(
                                        id,
                                        "rollback",
                                        "note.md",
                                        1,
                                        "body",
                                        null,
                                        java.util.List.of()));
                        assertThat(
                                        jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM knowledge_index_jobs WHERE document_id=?",
                                                Integer.class,
                                                id))
                                .isEqualTo(1);
                        tx.setRollbackOnly();
                    });
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM knowledge_index_jobs WHERE document_id=?",
                                    Integer.class,
                                    id))
                    .isZero();
        }
    }

    @Nested
    @EnabledIfEnvironmentVariable(named = "RUN_MIDDLEWARE_TESTS", matches = "true")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                "knowledge.dispatch.mode=rocketmq",
                "app.run-dispatch.mode=rocketmq",
                "knowledge.dispatch.initial-delay-ms=600000",
                "knowledge.import.worker-enabled=false",
                "agent.vector.enabled=true",
                "agent.vector.worker-enabled=false",
                "app.outbox.publisher.enabled=false",
                "app.run-recovery.enabled=false",
                "interview.runs.worker-enabled=false"
            })
    class Broker extends Imports {
        @TempDir static Path shared;
        static final String profile = "knowledge-it-" + UUID.randomUUID();
        static final Duration invisible = Duration.ofSeconds(10);

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry p) {
            p.add("knowledge.import.shared-directory", () -> shared.toString());
            p.add(
                    "app.rocketmq.resource-prefix",
                    () -> {
                        var prefix = System.getenv("ROCKETMQ_RESOURCE_PREFIX");
                        if (prefix == null || !prefix.matches("it-[0-9a-f]{16}"))
                            throw new IllegalStateException(
                                    "Knowledge broker tests require a runner-owned resource prefix");
                        return prefix;
                    });
        }

        @Autowired KnowledgeTaskDispatch dispatch;
        @Autowired RocketTransport transport;
        @Autowired Environment env;

        @BeforeEach
        void configureProfile() {
            when(vectors.fingerprint()).thenReturn(profile);
        }

        @Override
        @AfterEach
        void deleteOwner() throws Exception {
            super.deleteOwner();
            jdbc.update("DELETE FROM knowledge_index_jobs WHERE profile=?", profile);
        }

        SimpleConsumer consumer(String kind) throws Exception {
            var configuration =
                    ClientConfiguration.newBuilder()
                            .setEndpoints(env.getRequiredProperty("app.rocketmq.endpoints"))
                            .enableSsl(env.getProperty("app.rocketmq.tls", Boolean.class, false))
                            .setRequestTimeout(Duration.ofSeconds(3));
            var key = env.getProperty("app.rocketmq.access-key", "");
            if (!key.isBlank())
                configuration.setCredentialProvider(
                        new StaticSessionCredentialsProvider(
                                key, env.getRequiredProperty("app.rocketmq.secret-key")));
            var client =
                    ClientServiceProvider.loadService()
                            .newSimpleConsumerBuilder()
                            .setClientConfiguration(configuration.build())
                            .setConsumerGroup(transport.resource(kind + "-workers"))
                            .setSubscriptionExpressions(
                                    Map.of(
                                            transport.resource("knowledge-" + kind),
                                            FilterExpression.SUB_ALL))
                            .setAwaitDuration(Duration.ofSeconds(5))
                            .build();
            // 在 API 发布任务前先拉取一次，建立新消费组的起始位点。
            try {
                for (var stale : client.receive(1, invisible)) client.ack(stale);
                return client;
            } catch (Exception ex) {
                client.close();
                throw ex;
            }
        }

        MessageView next(SimpleConsumer client, String key) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (System.nanoTime() < deadline) {
                for (var view : client.receive(1, invisible)) {
                    if (view.getKeys().contains(key)) return view;
                    client.ack(view);
                }
            }
            throw new AssertionError("Timed out receiving " + key);
        }

        RocketTransport.Delivery delivery(MessageView view) {
            var buffer = view.getBody().duplicate();
            var bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return new RocketTransport.Delivery(
                    view.getTopic(),
                    view.getMessageId().toString(),
                    List.copyOf(view.getKeys()),
                    bytes);
        }

        @Test
        void commitBeforeAckIsRedeliveredWithoutParsingTwice() throws Exception {
            String id = submit();
            assertThat(repository.pending()).contains(id);
            try (var client = consumer("import")) {
                dispatch.publish();
                var first = next(client, "import:" + id);
                assertThat(dispatch.receive("import", delivery(first))).isTrue();
                // 业务提交后故意不确认消息，两次领取之间也不补发任务，以验证 Broker 自身的重投。
                var retried = next(client, "import:" + id);
                assertThat(retried.getDeliveryAttempt()).isGreaterThan(first.getDeliveryAttempt());
                assertThat(retried.getMessageId().toString())
                        .isEqualTo(first.getMessageId().toString());
                assertThat(dispatch.receive("import", delivery(retried))).isTrue();
                client.ack(retried);
            }
            assertThat(status(id)).isEqualTo("READY");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT attempts FROM knowledge_import_jobs WHERE id=?",
                                    Integer.class,
                                    id))
                    .isEqualTo(1);
        }

        @Test
        void realListenerRecoversReservedNotificationAndCanceledJobsRemainCanceled()
                throws Exception {
            String id = submit(), canceled = submit();
            try (var ignored = consumer("import")) {
                assertThat(repository.reserveNotice(id)).isTrue();
                dispatch.publish();
                performDelete("/api/knowledge/imports/jobs/" + canceled, token);
                jdbc.update(
                        "UPDATE knowledge_import_jobs SET notice_after=CURRENT_TIMESTAMP(6) WHERE id=?",
                        id);
                dispatch.publish();
            }
            var processed = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
            var latch = new CountDownLatch(2);
            transport.listen(
                    "import-workers",
                    List.of("knowledge-import"),
                    1,
                    notice -> {
                        boolean ack = dispatch.receive("import", notice);
                        if (ack
                                && (notice.keys().contains("import:" + id)
                                        || notice.keys().contains("import:" + canceled))
                                && processed.add(notice.keys().getFirst())) latch.countDown();
                        return ack;
                    });
            assertThat(latch.await(60, TimeUnit.SECONDS)).isTrue();
            assertThat(status(id)).isEqualTo("READY");
            assertThat(status(canceled)).isEqualTo("CANCELED");
        }

        @Test
        void indexNotificationForDeletedDocumentOnlyRemovesVectors() throws Exception {
            var response =
                    performPost(
                            "/api/knowledge/documents",
                            Map.of("title", "index", "content", "version one"),
                            token);
            String id =
                    objectMapper
                            .readTree(response.getResponse().getContentAsString())
                            .at("/data/id")
                            .asString();
            var job =
                    jdbc.queryForObject(
                            "SELECT id FROM knowledge_index_jobs WHERE document_id=? AND profile=?",
                            Long.class,
                            id,
                            profile);
            try (var client = consumer("index")) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (Boolean.FALSE.equals(
                        jdbc.queryForObject(
                                "SELECT notice_after > CURRENT_TIMESTAMP(6) FROM knowledge_index_jobs WHERE id=?",
                                Boolean.class,
                                job))) {
                    assertThat(System.nanoTime())
                            .as("expected an index notice for job %s", job)
                            .isLessThan(deadline);
                    dispatch.publish();
                }
                performDelete("/api/knowledge/documents/" + id, token);
                var notice = next(client, "index:" + job);
                assertThat(dispatch.receive("index", delivery(notice))).isTrue();
                assertThat(dispatch.receive("index", delivery(notice))).isTrue();
                client.ack(notice);
            }
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT state FROM knowledge_index_jobs WHERE id=?",
                                    String.class,
                                    job))
                    .isEqualTo("DELETED");
            verify(vectors, never()).index(any(), anyList());
            verify(vectors).remove(any(), eq(profile));
        }
    }
}
