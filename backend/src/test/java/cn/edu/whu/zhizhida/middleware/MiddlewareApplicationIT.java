package cn.edu.whu.zhizhida.middleware;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import cn.edu.whu.zhizhida.interview.api.QuestionCatalog;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunStore;
import cn.edu.whu.zhizhida.interview.execution.RunDispatchConsumer;
import cn.edu.whu.zhizhida.interview.execution.RunDispatchOutbox;
import cn.edu.whu.zhizhida.interview.execution.RunDispatchPublisher;
import cn.edu.whu.zhizhida.messaging.RocketTransport;
import io.micrometer.core.instrument.MeterRegistry;

/** 使用生产实现的 API、持久化、发布器与消费者，并连接真实 Redis 和 RocketMQ。 */
@EnabledIfEnvironmentVariable(named = "RUN_MIDDLEWARE_TESTS", matches = "true")
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "app.cache.questions.enabled=true",
                "app.rate-limit.redis.enabled=true",
                "app.rate-limit.redis.limit=100",
                "app.run-dispatch.mode=rocketmq",
                "app.outbox.publisher.initial-delay-ms=600000",
                "app.run-recovery.enabled=false",
                "interview.runs.worker-enabled=false",
                "spring.data.redis.connect-timeout=500ms",
                "spring.data.redis.timeout=300ms"
})
class MiddlewareApplicationIT extends IntegrationTestBase {
        static final String namespace = "zhizhida.test." + UUID.randomUUID();

        static String env(String key, String fallback) {
                return System.getenv().getOrDefault(key, fallback);
        }

        @DynamicPropertySource
        static void middleware(DynamicPropertyRegistry p) {
                // 故障用例和性能基准使用同一组 Outbox 批量发布参数。
                p.add("app.outbox.publisher.batch-size", () -> env("BENCHMARK_PUBLISHER_BATCH_SIZE", "1"));
                p.add(
                                "app.outbox.publisher.batch-budget-ms",
                                () -> env("BENCHMARK_PUBLISHER_BATCH_BUDGET_MS", "100"));
                p.add("app.redis.namespace", () -> namespace);
                TestBroker.properties(p);
                redisProperties().forEach((key, value) -> p.add(key, () -> value));
        }

        @Autowired
        InterviewRunStore store;
        @Autowired
        RunDispatchOutbox outbox;
        @Autowired
        RunDispatchPublisher publisher;
        @Autowired
        RunDispatchConsumer consumer;
        @Autowired
        RocketTransport transport;
        SimpleConsumer client;

        @Autowired
        @org.springframework.beans.factory.annotation.Qualifier("submissionRedisTemplate")
        StringRedisTemplate redis;

        @Autowired
        @org.springframework.beans.factory.annotation.Qualifier("questionCacheRedisTemplate")
        StringRedisTemplate cacheRedis;

        @Autowired
        JdbcTemplate jdbc;
        @Autowired
        QuestionCatalog catalog;
        @Autowired
        MeterRegistry metrics;
        @Autowired
        org.springframework.context.ApplicationContext application;
        @org.springframework.boot.test.web.server.LocalServerPort
        int port;
        String token;
        long owner;
        String snapshotKey;

        @BeforeEach
        void setup() throws Exception {
                client = TestBroker.open();
                // 在 API 发布任务前先拉取一次，建立测试消费组的起始位点。
                client.receive(1, java.time.Duration.ofSeconds(12));
                token = registerUniqueUserToken("password123", "中间件业务验证");
                owner = ((Number) data(performGet("/api/users/me", token)).get("id")).longValue();
        }

        @AfterEach
        void cleanup() throws Exception {
                try {
                        if (token != null)
                                performDeleteWithBody(
                                                "/api/users/me", Map.of("currentPassword", "password123"), token);
                } finally {
                        redis.delete(namespace + ":rate:ai-submit:" + owner);
                        if (snapshotKey != null)
                                cacheRedis.delete(snapshotKey);
                        if (client != null)
                                client.close();
                }
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data(org.springframework.test.web.servlet.MvcResult result)
                        throws Exception {
                return (Map<String, Object>) parseJsonResult(result).get("data");
        }

        String start() throws Exception {
                return start(token);
        }

        String start(String userToken) throws Exception {
                var result = performPost(
                                "/api/interviews/start-commands",
                                Map.of(
                                                "requestId",
                                                UUID.randomUUID().toString(),
                                                "configuration",
                                                Map.of(
                                                                "targetRole",
                                                                "Java 后端",
                                                                "totalRounds",
                                                                3,
                                                                "experienceMode",
                                                                "FORMAL")),
                                userToken);
                assertThat(result.getResponse().getStatus()).isEqualTo(202);
                return (String) data(result).get("runId");
        }

        MessageView receive(String topic, boolean ack) throws Exception {
                MessageView message = TestBroker.receive(client, topic);
                assertThat(consumer.receive(TestBroker.delivery(message))).isTrue();
                if (ack)
                        client.ack(message);
                return message;
        }

        @Test
        @EnabledIfEnvironmentVariable(named = "RUN_BROKER_FAULT_TESTS", matches = "true")
        void databaseOutageMovesOriginalToRetryBeforeAckAndCommitsOnceAfterRecovery() throws Exception {
                String id = start();
                extendTestQueueDeadline(id);
                publisher.publish();
                {
                        var original = TestBroker.receive(client, "run-ready");
                        docker("MYSQL_TEST_CONTAINER", "stop", "--time", "5");
                        try {
                                assertThat(consumer.receive(TestBroker.delivery(original))).isTrue();
                                client.ack(original);
                        } finally {
                                docker("MYSQL_TEST_CONTAINER", "start");
                        }
                        awaitDatabase();
                        assertThat(store.get(owner, id).status()).isEqualTo("ACCEPTED");
                        assertThat(store.get(owner, id).attempts()).isZero();
                        receive("run-delay", true);
                        assertSingleCommit(id);
                }
        }

        @Test
        @EnabledIfEnvironmentVariable(named = "RUN_BROKER_FAULT_TESTS", matches = "true")
        void globalCapacityRejectionUsesDelayedBrokerRetryWithoutSpendingModelBudget()
                        throws Exception {
                var users = new java.util.ArrayList<String>();
                var claims = new java.util.ArrayList<InterviewRunStore.Claim>();
                try {
                        int capacity = application
                                        .getEnvironment()
                                        .getProperty("interview.runs.capacity.max-active", Integer.class, 4);
                        for (int i = 0; i < capacity; i++) {
                                String user = registerUniqueUserToken("password123", "占用执行容量");
                                users.add(user);
                                long userId = ((Number) data(performGet("/api/users/me", user)).get("id")).longValue();
                                String busy = start(user);
                                claims.add(store.claim(store.get(userId, busy)));
                                jdbc.update(
                                                "UPDATE interview_run_outbox SET status='PUBLISHED' WHERE run_id=?",
                                                busy);
                        }
                        String id = start();
                        extendTestQueueDeadline(id);
                        publisher.publish();
                        {
                                receive("run-ready", true);
                                assertThat(store.get(owner, id).status()).isEqualTo("ACCEPTED");
                                assertThat(store.get(owner, id).attempts()).isZero();
                                claims.forEach(claim -> store.fail(claim, "TEST_CAPACITY_RELEASED"));
                                receive("run-delay", true);
                                assertSingleCommit(id);
                        }
                } finally {
                        for (String user : users)
                                performDeleteWithBody(
                                                "/api/users/me", Map.of("currentPassword", "password123"), user);
                }
        }

        @Test
        @EnabledIfEnvironmentVariable(named = "RUN_BROKER_FAULT_TESTS", matches = "true")
        void confirmedDelaySurvivesBrokerRestart() throws Exception {
                String id = start();
                extendTestQueueDeadline(id);
                var deadline = store.get(owner, id).deadline();
                jdbc.update(
                                "UPDATE interview_runs SET not_before=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP(6)) WHERE id=?",
                                id);
                publisher.publish();
                receive("run-ready", true);
                assertThat(store.get(owner, id).attempts()).isZero();
                docker("MQ_TEST_CONTAINER", "kill");
                docker("MQ_TEST_CONTAINER", "start");
                awaitBroker();
                jdbc.update("UPDATE interview_runs SET not_before=NULL WHERE id=?", id);
                receive("run-delay", true);
                assertSingleCommit(id);
                assertThat(store.get(owner, id).deadline()).isEqualTo(deadline);
        }

        @Test
        @EnabledIfEnvironmentVariable(named = "RUN_BROKER_FAULT_TESTS", matches = "true")
        @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
        void proxyRestartResumesBufferedWork() throws Exception {
                client.close();
                client = null;
                transport.listen(
                                "run-workers", java.util.List.of("run-ready", "run-delay"), 1, consumer::receive);
                docker("MQ_PROXY_TEST_CONTAINER", "stop", "--time", "3");
                String id;
                try {
                        id = start();
                        extendTestQueueDeadline(id);
                        publisher.publish();
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT status FROM interview_run_outbox WHERE run_id=?",
                                                        String.class,
                                                        id))
                                        .isEqualTo("PENDING");
                } finally {
                        docker("MQ_PROXY_TEST_CONTAINER", "start");
                }
                awaitBroker();
                jdbc.update(
                                "UPDATE interview_run_outbox SET next_attempt_at=CURRENT_TIMESTAMP(6) WHERE run_id=?",
                                id);
                publisher.publish();
                long until = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
                while (!store.get(owner, id).terminal() && System.nanoTime() < until) {
                        publisher.publish();
                        Thread.sleep(100);
                }
                transport.pause("run-workers");

                assertSingleCommit(id);
        }

        void extendTestQueueDeadline(String id) {
                jdbc.update(
                                "UPDATE interview_runs SET queue_deadline=TIMESTAMPADD(SECOND,90,CURRENT_TIMESTAMP(6)),"
                                                + "deadline=TIMESTAMPADD(SECOND,180,CURRENT_TIMESTAMP(6)) WHERE id=?",
                                id);
        }

        void assertSingleCommit(String id) {
                var row = store.get(owner, id);
                assertThat(row.status()).isEqualTo("SUCCEEDED");
                assertThat(store.events(owner, id, 0))
                                .filteredOn(event -> event.type().equals("turn.committed"))
                                .hasSize(1);
                assertThat(
                                jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM interview_sessions WHERE session_id=?",
                                                Integer.class,
                                                row.sessionId()))
                                .isEqualTo(1);
        }

        void awaitDatabase() throws Exception {
                long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(40);
                while (System.nanoTime() < until) {
                        try {
                                if (Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class)))
                                        return;
                        } catch (org.springframework.dao.DataAccessException unavailable) {
                        }
                        Thread.sleep(200);
                }
                throw new AssertionError("Owned test database did not restart");
        }

        void awaitBroker() throws Exception {
                long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(40);
                while (System.nanoTime() < until) {
                        try {
                                // Broker 重启后可能仍在恢复消息，缓存的路由此时仍可用。以实际发送回执判定就绪。
                                transport.send("test-invalid-source", "recovery-probe", new byte[] { 0 });
                                return;
                        } catch (RuntimeException unavailable) {
                        }
                        Thread.sleep(200);
                }
                throw new AssertionError("Owned test broker did not restart");
        }

        void docker(String variable, String operation, String... options) throws Exception {
                String container = System.getenv(variable), label = System.getenv("MQ_ACCEPTANCE_RUN");
                assertThat(container).isNotBlank();
                assertThat(label).isNotBlank();
                var inspect = new ProcessBuilder(
                                "docker",
                                "inspect",
                                "--format",
                                "{{json .Config.Labels}}",
                                container)
                                .redirectErrorStream(true)
                                .start();
                assertThat(inspect.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(inspect.exitValue()).isZero();
                var labels = objectMapper.readTree(inspect.getInputStream().readAllBytes());
                if (variable.equals("MYSQL_TEST_CONTAINER")) {
                        assertThat(labels.path("zhizhida.mq.acceptance").asString()).isEqualTo(label);
                } else {
                        assertThat(labels.path("com.docker.compose.project").asString())
                                        .isEqualTo("zdmq" + label);
                        assertThat(labels.path("com.docker.compose.service").asString())
                                        .isEqualTo(variable.equals("MQ_PROXY_TEST_CONTAINER") ? "mqproxy" : "mqbroker");
                }
                var command = new java.util.ArrayList<>(java.util.List.of("docker", operation));
                command.addAll(java.util.List.of(options));
                command.add(container);
                var process = new ProcessBuilder(command).redirectErrorStream(true).start();
                assertThat(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue()).isZero();
        }

        @Test
        void cacheHitAvoidsAnotherBodyReadButStillChecksPublication() {
                double source = metrics.counter("zhizhida.question.cache", "outcome", "source").count();
                int version = jdbc.queryForObject(
                                "SELECT version FROM question_bank WHERE id='qb-java-001'", Integer.class);
                snapshotKey = namespace + ":question:snapshot:s1:qb-java-001:v" + version;
                var first = catalog.requirePublishedForExecution(owner, "qb-java-001", version);
                assertThat(catalog.requirePublishedForExecution(owner, "qb-java-001", version))
                                .isEqualTo(first);
                assertThat(metrics.counter("zhizhida.question.cache", "outcome", "source").count())
                                .isEqualTo(source + 1);
                assertThat(cacheRedis.hasKey(snapshotKey)).isTrue();
                assertThat(redis.hasKey(snapshotKey)).isFalse();
                assertThatThrownBy(() -> catalog.requirePublishedForExecution(owner, "qb-java-001", 999))
                                .isInstanceOf(cn.edu.whu.zhizhida.common.BusinessException.class);
        }

        @Test
        void abandonedPublishLeaseAndLostAckDoNotDuplicateBusinessCommit() throws Exception {
                String id = start();
                var deadline = store.get(owner, id).deadline();
                assertThat(outbox.claim()).isPresent(); // 模拟领取成功后、发送前进程停止。
                jdbc.update(
                                "UPDATE interview_run_outbox SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE run_id=?",
                                id);
                publisher.publish();
                assertThat(
                                jdbc.queryForObject(
                                                "SELECT status FROM interview_run_outbox WHERE run_id=?",
                                                String.class,
                                                id))
                                .isEqualTo("PUBLISHED");
                {
                        // 首次投递完成业务提交后跳过确认，等待原消息重新可见。
                        var first = receive("run-ready", false);
                        assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
                        var committed = store.get(owner, id);
                        var events = store.events(owner, id, 0);
                        var quota = jdbc.queryForList(
                                        "SELECT * FROM request_quotas ORDER BY scope_key,window_start");
                        var again = receive("run-ready", true);
                        assertThat(again.getMessageId()).isEqualTo(first.getMessageId());
                        assertThat(again.getDeliveryAttempt()).isGreaterThan(first.getDeliveryAttempt());
                        assertThat(store.get(owner, id)).isEqualTo(committed);
                        assertThat(store.events(owner, id, 0)).isEqualTo(events);
                        assertThat(
                                        jdbc.queryForList(
                                                        "SELECT * FROM request_quotas ORDER BY scope_key,window_start"))
                                        .isEqualTo(quota);
                        assertThat(committed.deadline()).isEqualTo(deadline);
                }
        }

        @Test
        @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
        void actualListenerExecutesAnApiAcceptedRun() throws Exception {
                client.close();
                client = null;
                transport.listen(
                                "run-workers", java.util.List.of("run-ready", "run-delay"), 1, consumer::receive);
                try {
                        String id = start();
                        publisher.publish();
                        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                        while (!store.get(owner, id).terminal() && System.nanoTime() < until)
                                Thread.sleep(50);
                        assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
                        assertThat(store.events(owner, id, 0))
                                        .anyMatch(event -> event.type().equals("turn.committed"));
                } finally {
                        transport.pause("run-workers");
                }
        }

        @Test
        void twoIndependentApiInstancesShareTheSubmissionWindow() throws Exception {
                // 两个应用上下文均受 TestDatabaseGuard 保护并使用可丢弃数据库，不加载开发环境 .env。
                try (var other = otherApi(Map.of())) {
                        int otherPort = Integer.parseInt(
                                        other.getEnvironment().getRequiredProperty("local.server.port"));
                        redis.opsForValue()
                                        .set(
                                                        namespace + ":rate:ai-submit:" + owner,
                                                        "99",
                                                        java.time.Duration.ofSeconds(60));
                        String commandId = UUID.randomUUID().toString();
                        var accepted = postStart(port, commandId);
                        assertThat(accepted.statusCode()).isEqualTo(202);
                        String runId = objectMapper.readTree(accepted.body()).at("/data/runId").asString();
                        assertThat(runId).isNotBlank();

                        var rejected = postStart(otherPort, UUID.randomUUID().toString());
                        assertThat(rejected.statusCode()).isEqualTo(429);
                        assertThat(rejected.headers().firstValueAsLong("Retry-After").orElseThrow())
                                        .isBetween(1L, 60L);

                        // 浏览器在响应丢失后可能向另一实例重放；已受理的命令即使窗口已满也应能取回。
                        var replay = postStart(otherPort, commandId);
                        assertThat(replay.statusCode()).isEqualTo(202);
                        assertThat(objectMapper.readTree(replay.body()).at("/data/runId").asString())
                                        .isEqualTo(runId);
                        assertThat(redis.opsForValue().get(namespace + ":rate:ai-submit:" + owner))
                                        .isEqualTo("100");
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT COUNT(*) FROM interview_runs WHERE owner_id=?",
                                                        Long.class,
                                                        owner))
                                        .isEqualTo(1);
                }
        }

        static Map<String, Object> redisProperties() {
                return Map.of(
                                "app.redis.cache.host",
                                env("REDIS_CACHE_HOST", "127.0.0.1"),
                                "app.redis.cache.port",
                                env("REDIS_CACHE_PORT", "6380"),
                                "app.redis.cache.password",
                                env("REDIS_CACHE_PASSWORD", ""),
                                "app.redis.rate-limit.host",
                                env("REDIS_RATE_HOST", env("REDIS_HOST", "127.0.0.1")),
                                "app.redis.rate-limit.port",
                                env("REDIS_RATE_PORT", env("REDIS_PORT", "6379")),
                                "app.redis.rate-limit.password",
                                env("REDIS_RATE_PASSWORD", env("REDIS_PASSWORD", "")));
        }

        org.springframework.context.ConfigurableApplicationContext otherApi(
                        Map<String, Object> overrides) {
                var properties = new java.util.HashMap<String, Object>(redisProperties());
                properties.putAll(
                                Map.of(
                                                "spring.config.location",
                                                "classpath:/application-it.yml",
                                                "app.rate-limit.redis.enabled",
                                                "true",
                                                "app.rate-limit.redis.limit",
                                                "100",
                                                "app.cache.questions.enabled",
                                                "true",
                                                "app.redis.namespace",
                                                namespace));
                properties.putAll(overrides);
                return new org.springframework.boot.builder.SpringApplicationBuilder(
                                cn.edu.whu.zhizhida.ZhizhidaApplication.class)
                                .profiles("it")
                                .initializers(new cn.edu.whu.zhizhida.TestDatabaseGuard())
                                .run(
                                                properties.entrySet().stream()
                                                                .map(entry -> "--" + entry.getKey() + "="
                                                                                + entry.getValue())
                                                                .toArray(String[]::new));
        }

        int postStart(int apiPort) throws Exception {
                return postStart(apiPort, UUID.randomUUID().toString()).statusCode();
        }

        java.net.http.HttpResponse<String> postStart(int apiPort, String commandId) throws Exception {
                try (var client = java.net.http.HttpClient.newHttpClient()) {
                        String body = objectMapper.writeValueAsString(
                                        Map.of(
                                                        "requestId",
                                                        commandId,
                                                        "configuration",
                                                        Map.of(
                                                                        "targetRole",
                                                                        "Java 后端",
                                                                        "totalRounds",
                                                                        3,
                                                                        "experienceMode",
                                                                        "FORMAL")));
                        var request = java.net.http.HttpRequest.newBuilder(
                                        java.net.URI.create(
                                                        "http://127.0.0.1:"
                                                                        + apiPort
                                                                        + "/api/interviews/start-commands"))
                                        .header("Authorization", "Bearer " + token)
                                        .header("Content-Type", "application/json")
                                        .timeout(java.time.Duration.ofSeconds(10))
                                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                                        .build();
                        return client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                }
        }

        @Test
        void cacheOutageFallsBackAndStillAcceptsSubmissions() throws Exception {
                try (var other = otherApi(
                                Map.of("app.redis.cache.host", "127.0.0.1", "app.redis.cache.port", "1"))) {
                        var otherMetrics = other.getBean(MeterRegistry.class);
                        var sources = otherMetrics.counter("zhizhida.question.cache", "outcome", "source");
                        double before = sources.count();
                        assertThat(
                                        other.getBean(QuestionCatalog.class)
                                                        .requirePublishedForExecution(owner, "qb-java-001", null)
                                                        .id())
                                        .isEqualTo("qb-java-001");
                        assertThat(sources.count()).isEqualTo(before + 1);
                        var health = other.getBean(cn.edu.whu.zhizhida.health.MiddlewareReadiness.class).inspect();
                        assertThat(health.cache()).isEqualTo("fallback");
                        assertThat(health.submissions()).isEqualTo("up");
                        assertThat(health.acceptsRequests()).isTrue();
                        assertThat(
                                        postStart(
                                                        Integer.parseInt(
                                                                        other.getEnvironment()
                                                                                        .getRequiredProperty(
                                                                                                        "local.server.port"))))
                                        .isEqualTo(202);
                        assertThat(redis.opsForValue().get(namespace + ":rate:ai-submit:" + owner))
                                        .isEqualTo("1");
                }
        }

        @Test
        void rateOutageRejectsSubmissionButKeepsCacheHits() throws Exception {
                cacheHitAvoidsAnotherBodyReadButStillChecksPublication();
                try (var other = otherApi(
                                Map.of(
                                                "app.redis.rate-limit.host",
                                                "127.0.0.1",
                                                "app.redis.rate-limit.port",
                                                "1"))) {
                        var hits = other.getBean(MeterRegistry.class)
                                        .counter("zhizhida.question.cache", "outcome", "hit");
                        double before = hits.count();
                        other.getBean(QuestionCatalog.class)
                                        .requirePublishedForExecution(owner, "qb-java-001", null);
                        assertThat(hits.count()).isEqualTo(before + 1);
                        var health = other.getBean(cn.edu.whu.zhizhida.health.MiddlewareReadiness.class).inspect();
                        assertThat(health.cache()).isEqualTo("up");
                        assertThat(health.submissions()).isEqualTo("unavailable");
                        assertThat(
                                        postStart(
                                                        Integer.parseInt(
                                                                        other.getEnvironment()
                                                                                        .getRequiredProperty(
                                                                                                        "local.server.port"))))
                                        .isEqualTo(503);
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT COUNT(*) FROM interview_runs WHERE owner_id=?",
                                                        Long.class,
                                                        owner))
                                        .isZero();
                }
        }

        @Test
        @EnabledIfEnvironmentVariable(named = "RUN_REDIS_PRESSURE_TESTS", matches = "true")
        void cacheEvictionDoesNotResetSubmissionWindow() throws Exception {
                // 仅由隔离运行器启用；其缓存限制为 8 MB，并使用 allkeys-lru 淘汰策略。
                String rateKey = namespace + ":rate:ai-submit:" + owner;
                redis.opsForValue().set(rateKey, "99", java.time.Duration.ofSeconds(60));
                long ttl = redis.getExpire(rateKey, java.util.concurrent.TimeUnit.MILLISECONDS);
                long before = evictedKeys();
                var keys = new java.util.ArrayList<String>();
                try {
                        String body = "x".repeat(256 * 1024);
                        for (int i = 0; i < 128 && evictedKeys() == before; i++) {
                                String key = namespace + ":pressure:" + i;
                                keys.add(key);
                                cacheRedis.opsForValue().set(key, body, java.time.Duration.ofSeconds(30));
                        }
                        assertThat(evictedKeys()).isGreaterThan(before);
                        assertThat(redis.opsForValue().get(rateKey)).isEqualTo("99");
                        assertThat(redis.getExpire(rateKey, java.util.concurrent.TimeUnit.MILLISECONDS))
                                        .isBetween(1L, ttl);
                        assertThat(cacheRedis.hasKey(rateKey)).isFalse();
                        assertThat(postStart(port)).isEqualTo(202);
                        assertThat(postStart(port)).isEqualTo(429);
                } finally {
                        if (!keys.isEmpty())
                                cacheRedis.delete(keys);
                }
        }

        long evictedKeys() {
                var stats = cacheRedis.execute(
                                (org.springframework.data.redis.core.RedisCallback<java.util.Properties>) connection -> connection
                                                .serverCommands().info("stats"));
                return Long.parseLong(stats.getProperty("evicted_keys"));
        }

        @Test
        void queuedCancellationAndExpiredBudgetRemainTerminalAfterDelivery() throws Exception {
                {
                        String cancelled = start();
                        publisher.publish();
                        store.cancel(owner, cancelled);
                        receive("run-ready", true);
                        assertThat(store.get(owner, cancelled).status()).isEqualTo("CANCELLED");
                        String expired = start();
                        publisher.publish();
                        jdbc.update(
                                        "UPDATE interview_runs SET deadline=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
                                        expired);
                        var deadline = store.get(owner, expired).deadline();
                        store.recoverDispatches();
                        receive("run-ready", true);
                        assertThat(store.get(owner, expired).status()).isEqualTo("EXPIRED");
                        assertThat(store.get(owner, expired).attempts()).isZero();
                        assertThat(store.get(owner, expired).deadline()).isEqualTo(deadline);
                }
        }

        @Test
        void deletedKnowledgeIsRejectedWhenItsQueuedMessageIsFinallyDelivered() throws Exception {
                var document = data(
                                performPost(
                                                "/api/knowledge/documents",
                                                Map.of("title", "幂等笔记", "content", "# 幂等\n使用请求号唯一约束，并发冲突后读取已保存结果。"),
                                                token));
                String documentId = (String) document.get("id");
                var task = data(
                                performPost(
                                                "/api/interviews/training-tasks/from-source",
                                                Map.of(
                                                                "requestId",
                                                                UUID.randomUUID().toString(),
                                                                "sourceType",
                                                                "KNOWLEDGE",
                                                                "documentId",
                                                                documentId,
                                                                "documentVersion",
                                                                1,
                                                                "trainingTopic",
                                                                "DATABASE"),
                                                token));
                var accepted = performPost(
                                "/api/interviews/training-tasks/" + task.get("id") + "/start-commands",
                                Map.of("requestId", UUID.randomUUID().toString()),
                                token);
                assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
                String id = (String) data(accepted).get("runId");
                publisher.publish();
                assertThat(
                                performDelete("/api/knowledge/documents/" + documentId, token)
                                                .getResponse()
                                                .getStatus())
                                .isEqualTo(200);
                store.recoverDispatches();
                {
                        receive("run-ready", true);
                }
                assertThat(store.get(owner, id).status()).isEqualTo("FAILED");
                assertThat(store.get(owner, id).attempts()).isZero();
        }

        @Test
        void brokerOutageKeepsOutboxAndRecoveryDoesNotRenewExpiredBudget() throws Exception {
                String id = start();
                var unreachable = new RocketTransport(
                                new org.springframework.mock.env.MockEnvironment()
                                                .withProperty("app.rocketmq.endpoints", "127.0.0.1:1"),
                                objectMapper,
                                metrics);
                try {
                        new RunDispatchPublisher(
                                        outbox,
                                        unreachable,
                                        objectMapper,
                                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                                        Integer.parseInt(env("BENCHMARK_PUBLISHER_BATCH_SIZE", "1")),
                                        Long.parseLong(env("BENCHMARK_PUBLISHER_BATCH_BUDGET_MS", "100")))
                                        .publish();
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT status FROM interview_run_outbox WHERE run_id=?",
                                                        String.class,
                                                        id))
                                        .isEqualTo("PENDING");
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT last_error_code FROM interview_run_outbox WHERE run_id=?",
                                                        String.class,
                                                        id))
                                        .isEqualTo("PUBLISH_UNAVAILABLE");
                        jdbc.update(
                                        "UPDATE interview_runs SET deadline=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
                                        id);
                        var deadline = store.get(owner, id).deadline();
                        store.recoverDispatches();
                        publisher.publish(); // 此时真实消息代理已恢复可用。
                        assertThat(store.get(owner, id).status()).isEqualTo("EXPIRED");
                        assertThat(store.get(owner, id).deadline()).isEqualTo(deadline);
                        assertThat(store.get(owner, id).attempts()).isZero();
                        assertThat(outbox.pending()).isZero();
                } finally {
                        unreachable.close();
                }
        }

        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
        void revokedQuestionCannotExecuteAfterBrokerRecoveryEvenWithAWarmCache(boolean unpublish)
                        throws Exception {
                String questionId = "qb-java-001";
                int version = jdbc.queryForObject(
                                "SELECT version FROM question_bank WHERE id=?", Integer.class, questionId);
                snapshotKey = namespace + ":question:snapshot:s1:" + questionId + ":v" + version;
                var task = data(
                                performPost(
                                                "/api/interviews/training-tasks/from-source",
                                                Map.of(
                                                                "requestId",
                                                                UUID.randomUUID().toString(),
                                                                "sourceType",
                                                                "QUESTION",
                                                                "questionId",
                                                                questionId,
                                                                "questionVersion",
                                                                version),
                                                token));
                assertThat(cacheRedis.hasKey(snapshotKey)).isTrue();
                String requestId = UUID.randomUUID().toString();
                String path = "/api/interviews/training-tasks/" + task.get("id") + "/start-commands";
                var accepted = performPost(path, Map.of("requestId", requestId), token);
                assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
                String id = (String) data(accepted).get("runId");
                var deadline = store.get(owner, id).deadline();
                var unreachable = new RocketTransport(
                                new org.springframework.mock.env.MockEnvironment()
                                                .withProperty("app.rocketmq.endpoints", "127.0.0.1:1"),
                                objectMapper,
                                metrics);
                try {
                        new RunDispatchPublisher(
                                        outbox,
                                        unreachable,
                                        objectMapper,
                                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                                        Integer.parseInt(env("BENCHMARK_PUBLISHER_BATCH_SIZE", "1")),
                                        Long.parseLong(env("BENCHMARK_PUBLISHER_BATCH_BUDGET_MS", "100")))
                                        .publish();
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT status FROM interview_run_outbox WHERE run_id=?",
                                                        String.class,
                                                        id))
                                        .isEqualTo("PENDING");
                        // 测试库没有题目编辑 HTTP API，直接提交下架或版本修订以模拟内容变更。
                        if (unpublish)
                                jdbc.update("UPDATE question_bank SET published=FALSE WHERE id=?", questionId);
                        else
                                jdbc.update("UPDATE question_bank SET version=version+1 WHERE id=?", questionId);
                        jdbc.update(
                                        "UPDATE interview_run_outbox SET next_attempt_at=CURRENT_TIMESTAMP(6) WHERE run_id=?",
                                        id);
                        publisher.publish();
                        {
                                var first = receive("run-ready", false);
                                var rejected = store.get(owner, id);
                                assertThat(rejected.status()).isEqualTo("FAILED");
                                assertThat(rejected.attempts()).isZero();
                                assertThat(rejected.deadline()).isEqualTo(deadline);
                                assertThat(store.events(owner, id, 0))
                                                .noneMatch(event -> event.type().equals("turn.committed"));
                                var quota = jdbc.queryForList(
                                                "SELECT * FROM request_quotas ORDER BY scope_key,window_start");
                                var again = receive("run-ready", true);
                                assertThat(again.getMessageId()).isEqualTo(first.getMessageId());
                                assertThat(store.get(owner, id)).isEqualTo(rejected);
                                assertThat(
                                                jdbc.queryForList(
                                                                "SELECT * FROM request_quotas ORDER BY scope_key,window_start"))
                                                .isEqualTo(quota);
                                assertThat(
                                                data(performPost(path, Map.of("requestId", requestId), token))
                                                                .get("runId"))
                                                .isEqualTo(id);
                        }
                } finally {
                        unreachable.close();
                        jdbc.update(
                                        "UPDATE question_bank SET version=?,published=TRUE WHERE id=?",
                                        version,
                                        questionId);
                }
        }
}
