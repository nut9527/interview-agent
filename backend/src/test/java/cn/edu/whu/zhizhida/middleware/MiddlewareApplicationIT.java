package cn.edu.whu.zhizhida.middleware;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import cn.edu.whu.zhizhida.interview.api.QuestionCatalog;
import cn.edu.whu.zhizhida.interview.execution.*;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** 使用生产实现的 API、持久化、发布器与消费者，并连接真实 Redis 和 RabbitMQ。 */
@EnabledIfEnvironmentVariable(named="RUN_MIDDLEWARE_TESTS", matches="true")
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.cache.questions.enabled=true", "app.rate-limit.redis.enabled=true", "app.rate-limit.redis.limit=100",
        "app.run-dispatch.mode=rabbitmq", "app.outbox.publisher.initial-delay-ms=600000",
        "app.run-recovery.enabled=false", "interview.runs.worker-enabled=false",
        "spring.rabbitmq.publisher-confirm-type=correlated", "spring.rabbitmq.publisher-returns=true",
        "spring.rabbitmq.connection-timeout=2s", "spring.data.redis.connect-timeout=500ms", "spring.data.redis.timeout=300ms"})
class MiddlewareApplicationIT extends IntegrationTestBase {
    static final String namespace = "zhizhida.test." + UUID.randomUUID();
    static String env(String key, String fallback) { return System.getenv().getOrDefault(key, fallback); }
    @DynamicPropertySource static void middleware(DynamicPropertyRegistry p) {
        // 候选批次参数也须经过故障用例验证，不能只测试吞吐量。
        p.add("app.outbox.publisher.batch-size", () -> env("BENCHMARK_PUBLISHER_BATCH_SIZE", "1"));
        p.add("app.outbox.publisher.batch-budget-ms", () -> env("BENCHMARK_PUBLISHER_BATCH_BUDGET_MS", "100"));
        p.add("app.redis.namespace", () -> namespace);
        p.add("app.run-dispatch.namespace", () -> namespace);
        redisProperties().forEach((key, value) -> p.add(key, () -> value));
        p.add("spring.rabbitmq.host", () -> env("RABBITMQ_HOST", "127.0.0.1"));
        p.add("spring.rabbitmq.port", () -> env("RABBITMQ_PORT", "5672"));
        p.add("spring.rabbitmq.username", () -> env("RABBITMQ_USERNAME", "zhizhida"));
        p.add("spring.rabbitmq.password", () -> env("RABBITMQ_PASSWORD", "zhizhida-local"));
        p.add("spring.rabbitmq.virtual-host", () -> env("RABBITMQ_VHOST", "/zhizhida-lab"));
    }
    @Autowired InterviewRunStore store;
    @Autowired RunDispatchOutbox outbox;
    @Autowired RunDispatchPublisher publisher;
    @Autowired RunDispatchConsumer consumer;
    @Autowired CachingConnectionFactory connection;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("submissionRedisTemplate") StringRedisTemplate redis;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("questionCacheRedisTemplate") StringRedisTemplate cacheRedis;
    @Autowired JdbcTemplate jdbc;
    @Autowired QuestionCatalog catalog;
    @Autowired MeterRegistry metrics;
    @Autowired org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry listeners;
    @Autowired org.springframework.context.ApplicationContext application;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    String token;
    long owner;
    String snapshotKey;

    @BeforeEach void setup() throws Exception {
        var admin = new RabbitAdmin(connection);
        admin.setApplicationContext(application);
        admin.initialize();
        token = registerUniqueUserToken("password123", "中间件业务验证");
        owner = ((Number) data(performGet("/api/users/me", token)).get("id")).longValue();
    }
    @AfterEach void cleanup() throws Exception {
        try { if (token != null) performDeleteWithBody("/api/users/me", Map.of("currentPassword", "password123"), token); }
        finally {
            redis.delete(namespace + ":rate:ai-submit:" + owner);
            if (snapshotKey != null) cacheRedis.delete(snapshotKey);
            var admin = new RabbitAdmin(connection);
            admin.deleteQueue(namespace + ".run.q");
            admin.deleteQueue(namespace + ".dead.q");
            admin.deleteExchange(namespace + ".x");
            admin.deleteExchange(namespace + ".dlx");
        }
    }
    @SuppressWarnings("unchecked")
    Map<String,Object> data(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return (Map<String,Object>) parseJsonResult(result).get("data");
    }
    String start() throws Exception {
        var result = performPost("/api/interviews/start-commands", Map.of("requestId", UUID.randomUUID().toString(),
                "configuration", Map.of("targetRole", "Java 后端", "totalRounds", 3, "experienceMode", "FORMAL")), token);
        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        return (String) data(result).get("runId");
    }
    Message get(Channel channel, boolean redelivered) throws Exception {
        var delivery = channel.basicGet(namespace + ".run.q", false);
        assertThat(delivery).isNotNull();
        assertThat(delivery.getEnvelope().isRedeliver()).isEqualTo(redelivered);
        var props = new MessageProperties();
        props.setDeliveryTag(delivery.getEnvelope().getDeliveryTag());
        props.setMessageId(delivery.getProps().getMessageId());
        return new Message(delivery.getBody(), props);
    }
    @Test void cacheHitAvoidsAnotherBodyReadButStillChecksPublication() {
        double source = metrics.counter("zhizhida.question.cache", "outcome", "source").count();
        int version = jdbc.queryForObject("SELECT version FROM question_bank WHERE id='qb-java-001'", Integer.class);
        snapshotKey = namespace + ":question:snapshot:s1:qb-java-001:v" + version;
        var first = catalog.requirePublishedForExecution(owner, "qb-java-001", version);
        assertThat(catalog.requirePublishedForExecution(owner, "qb-java-001", version)).isEqualTo(first);
        assertThat(metrics.counter("zhizhida.question.cache", "outcome", "source").count()).isEqualTo(source + 1);
        assertThat(cacheRedis.hasKey(snapshotKey)).isTrue();
        assertThat(redis.hasKey(snapshotKey)).isFalse();
        assertThatThrownBy(() -> catalog.requirePublishedForExecution(owner, "qb-java-001", 999))
                .isInstanceOf(cn.edu.whu.zhizhida.common.BusinessException.class);
    }
    @Test void abandonedPublishLeaseAndLostAckDoNotDuplicateBusinessCommit() throws Exception {
        String id = start();
        var deadline = store.get(owner, id).deadline();
        assertThat(outbox.claim()).isPresent(); // 模拟领取成功后、发送前进程停止。
        jdbc.update("UPDATE interview_run_outbox SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE run_id=?", id);
        publisher.publish();
        assertThat(jdbc.queryForObject("SELECT status FROM interview_run_outbox WHERE run_id=?", String.class, id)).isEqualTo("PUBLISHED");
        try (var raw = connection.getRabbitConnectionFactory().newConnection()) {
            try (var channel = raw.createChannel()) {
                var lostAck = (Channel) Proxy.newProxyInstance(Channel.class.getClassLoader(), new Class<?>[]{Channel.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("basicAck")) throw new IOException("simulated disconnect before ACK");
                            return method.invoke(channel, args);
                        });
                assertThatThrownBy(() -> consumer.receive(get(channel, false), lostAck)).isInstanceOf(IOException.class);
                assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
            }
            var committed = store.get(owner, id);
            var events = store.events(owner, id, 0);
            var quota = jdbc.queryForList("SELECT * FROM request_quotas ORDER BY scope_key,window_start");
            try (var channel = raw.createChannel()) { consumer.receive(get(channel, true), channel); }
            assertThat(store.get(owner, id)).isEqualTo(committed);
            assertThat(store.events(owner, id, 0)).isEqualTo(events);
            assertThat(jdbc.queryForList("SELECT * FROM request_quotas ORDER BY scope_key,window_start")).isEqualTo(quota);
            assertThat(committed.deadline()).isEqualTo(deadline);
        }
    }
    @Test void actualListenerExecutesAnApiAcceptedRun() throws Exception {
        listeners.getListenerContainers().forEach(container -> container.start());
        try {
            String id = start();
            publisher.publish();
            long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (!store.get(owner, id).terminal() && System.nanoTime() < until) Thread.sleep(50);
            assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
            assertThat(store.events(owner, id, 0)).anyMatch(event -> event.type().equals("turn.committed"));
        } finally { listeners.getListenerContainers().forEach(container -> container.stop()); }
    }
    @Test void twoIndependentApiInstancesShareTheSubmissionWindow() throws Exception {
        // 两个应用上下文均受 TestDatabaseGuard 保护并使用可丢弃数据库，不加载开发环境 .env。
        try (var other = otherApi(Map.of())) {
            int otherPort = Integer.parseInt(other.getEnvironment().getRequiredProperty("local.server.port"));
            redis.opsForValue().set(namespace + ":rate:ai-submit:" + owner, "99", java.time.Duration.ofSeconds(60));
            String commandId = UUID.randomUUID().toString();
            var accepted = postStart(port, commandId);
            assertThat(accepted.statusCode()).isEqualTo(202);
            String runId = objectMapper.readTree(accepted.body()).at("/data/runId").asText();
            assertThat(runId).isNotBlank();

            var rejected = postStart(otherPort, UUID.randomUUID().toString());
            assertThat(rejected.statusCode()).isEqualTo(429);
            assertThat(rejected.headers().firstValueAsLong("Retry-After").orElseThrow()).isBetween(1L, 60L);

            // 浏览器在响应丢失后可能向另一实例重放；已受理的命令即使窗口已满也应能取回。
            var replay = postStart(otherPort, commandId);
            assertThat(replay.statusCode()).isEqualTo(202);
            assertThat(objectMapper.readTree(replay.body()).at("/data/runId").asText()).isEqualTo(runId);
            assertThat(redis.opsForValue().get(namespace + ":rate:ai-submit:" + owner)).isEqualTo("100");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interview_runs WHERE owner_id=?", Long.class, owner)).isEqualTo(1);
        }
    }

    static Map<String, Object> redisProperties() {
        return Map.of("app.redis.cache.host", env("REDIS_CACHE_HOST", "127.0.0.1"),
                "app.redis.cache.port", env("REDIS_CACHE_PORT", "6380"),
                "app.redis.cache.password", env("REDIS_CACHE_PASSWORD", ""),
                "app.redis.rate-limit.host", env("REDIS_RATE_HOST", env("REDIS_HOST", "127.0.0.1")),
                "app.redis.rate-limit.port", env("REDIS_RATE_PORT", env("REDIS_PORT", "6379")),
                "app.redis.rate-limit.password", env("REDIS_RATE_PASSWORD", env("REDIS_PASSWORD", "")));
    }

    org.springframework.context.ConfigurableApplicationContext otherApi(Map<String, Object> overrides) {
        var properties = new java.util.HashMap<String, Object>(redisProperties());
        properties.putAll(Map.of("spring.config.location", "classpath:/application-it.yml",
                "app.rate-limit.redis.enabled", "true", "app.rate-limit.redis.limit", "100",
                "app.cache.questions.enabled", "true", "app.redis.namespace", namespace));
        properties.putAll(overrides);
        return new org.springframework.boot.builder.SpringApplicationBuilder(cn.edu.whu.zhizhida.ZhizhidaApplication.class)
                .profiles("it").initializers(new cn.edu.whu.zhizhida.TestDatabaseGuard()).properties(properties).run();
    }

    int postStart(int apiPort) throws Exception {
        return postStart(apiPort, UUID.randomUUID().toString()).statusCode();
    }

    java.net.http.HttpResponse<String> postStart(int apiPort, String commandId) throws Exception {
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            String body = objectMapper.writeValueAsString(Map.of("requestId", commandId,
                    "configuration", Map.of("targetRole", "Java 后端", "totalRounds", 3, "experienceMode", "FORMAL")));
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + apiPort + "/api/interviews/start-commands"))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(10)).POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
            return client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test void cacheOutageFallsBackAndStillAcceptsSubmissions() throws Exception {
        try (var other = otherApi(Map.of("app.redis.cache.host", "127.0.0.1", "app.redis.cache.port", "1"))) {
            var otherMetrics = other.getBean(MeterRegistry.class);
            var sources = otherMetrics.counter("zhizhida.question.cache", "outcome", "source");
            double before = sources.count();
            assertThat(other.getBean(QuestionCatalog.class).requirePublishedForExecution(owner, "qb-java-001", null).id()).isEqualTo("qb-java-001");
            assertThat(sources.count()).isEqualTo(before + 1);
            var health = other.getBean(cn.edu.whu.zhizhida.health.MiddlewareReadiness.class).inspect();
            assertThat(health.cache()).isEqualTo("fallback");
            assertThat(health.submissions()).isEqualTo("up");
            assertThat(health.acceptsRequests()).isTrue();
            assertThat(postStart(Integer.parseInt(other.getEnvironment().getRequiredProperty("local.server.port")))).isEqualTo(202);
            assertThat(redis.opsForValue().get(namespace + ":rate:ai-submit:" + owner)).isEqualTo("1");
        }
    }

    @Test void rateOutageRejectsSubmissionButKeepsCacheHits() throws Exception {
        cacheHitAvoidsAnotherBodyReadButStillChecksPublication();
        try (var other = otherApi(Map.of("app.redis.rate-limit.host", "127.0.0.1", "app.redis.rate-limit.port", "1"))) {
            var hits = other.getBean(MeterRegistry.class).counter("zhizhida.question.cache", "outcome", "hit");
            double before = hits.count();
            other.getBean(QuestionCatalog.class).requirePublishedForExecution(owner, "qb-java-001", null);
            assertThat(hits.count()).isEqualTo(before + 1);
            var health = other.getBean(cn.edu.whu.zhizhida.health.MiddlewareReadiness.class).inspect();
            assertThat(health.cache()).isEqualTo("up");
            assertThat(health.submissions()).isEqualTo("unavailable");
            assertThat(postStart(Integer.parseInt(other.getEnvironment().getRequiredProperty("local.server.port")))).isEqualTo(503);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interview_runs WHERE owner_id=?", Long.class, owner)).isZero();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named="RUN_REDIS_PRESSURE_TESTS", matches="true")
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
            assertThat(redis.getExpire(rateKey, java.util.concurrent.TimeUnit.MILLISECONDS)).isBetween(1L, ttl);
            assertThat(cacheRedis.hasKey(rateKey)).isFalse();
            assertThat(postStart(port)).isEqualTo(202);
            assertThat(postStart(port)).isEqualTo(429);
        } finally { if (!keys.isEmpty()) cacheRedis.delete(keys); }
    }

    long evictedKeys() {
        var stats = cacheRedis.execute((org.springframework.data.redis.core.RedisCallback<java.util.Properties>)
                connection -> connection.serverCommands().info("stats"));
        return Long.parseLong(stats.getProperty("evicted_keys"));
    }
    @Test void queuedCancellationAndExpiredBudgetRemainTerminalAfterDelivery() throws Exception {
        try (var raw = connection.getRabbitConnectionFactory().newConnection(); var channel = raw.createChannel()) {
            String cancelled = start();
            publisher.publish();
            store.cancel(owner, cancelled);
            consumer.receive(get(channel, false), channel);
            assertThat(store.get(owner, cancelled).status()).isEqualTo("CANCELLED");
            String expired = start();
            publisher.publish();
            jdbc.update("UPDATE interview_runs SET deadline=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?", expired);
            var deadline = store.get(owner, expired).deadline();
            store.recoverDispatches();
            consumer.receive(get(channel, false), channel);
            assertThat(store.get(owner, expired).status()).isEqualTo("EXPIRED");
            assertThat(store.get(owner, expired).attempts()).isZero();
            assertThat(store.get(owner, expired).deadline()).isEqualTo(deadline);
        }
    }
    @Test void deletedKnowledgeIsRejectedWhenItsQueuedMessageIsFinallyDelivered() throws Exception {
        var document = data(performPost("/api/knowledge/documents", Map.of("title", "幂等笔记",
                "content", "# 幂等\n使用请求号唯一约束，并发冲突后读取已保存结果。"), token));
        String documentId = (String) document.get("id");
        var task = data(performPost("/api/interviews/training-tasks/from-source", Map.of(
                "requestId", UUID.randomUUID().toString(), "sourceType", "KNOWLEDGE", "documentId", documentId,
                "documentVersion", 1, "trainingTopic", "DATABASE"), token));
        var accepted = performPost("/api/interviews/training-tasks/" + task.get("id") + "/start-commands",
                Map.of("requestId", UUID.randomUUID().toString()), token);
        assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
        String id = (String) data(accepted).get("runId");
        publisher.publish();
        assertThat(performDelete("/api/knowledge/documents/" + documentId, token).getResponse().getStatus()).isEqualTo(200);
        store.recoverDispatches();
        try (var raw = connection.getRabbitConnectionFactory().newConnection(); var channel = raw.createChannel()) {
            consumer.receive(get(channel, false), channel);
        }
        assertThat(store.get(owner, id).status()).isEqualTo("FAILED");
        assertThat(store.get(owner, id).attempts()).isZero();
    }
    @Test void brokerOutageKeepsOutboxAndRecoveryDoesNotRenewExpiredBudget() throws Exception {
        String id = start();
        var unreachable = new CachingConnectionFactory("127.0.0.1", 1);
        unreachable.setConnectionTimeout(200);
        try {
            new RunDispatchPublisher(outbox, new org.springframework.amqp.rabbit.core.RabbitTemplate(unreachable),
                    objectMapper, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), "rabbitmq",
                    Integer.parseInt(env("BENCHMARK_PUBLISHER_BATCH_SIZE", "1")),
                    Long.parseLong(env("BENCHMARK_PUBLISHER_BATCH_BUDGET_MS", "100"))).publish();
            assertThat(jdbc.queryForObject("SELECT status FROM interview_run_outbox WHERE run_id=?", String.class, id)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT last_error_code FROM interview_run_outbox WHERE run_id=?", String.class, id)).isEqualTo("PUBLISH_UNAVAILABLE");
            jdbc.update("UPDATE interview_runs SET deadline=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?", id);
            var deadline = store.get(owner, id).deadline();
            store.recoverDispatches();
            publisher.publish(); // 此时真实消息代理已恢复可用。
            assertThat(store.get(owner, id).status()).isEqualTo("EXPIRED");
            assertThat(store.get(owner, id).deadline()).isEqualTo(deadline);
            assertThat(store.get(owner, id).attempts()).isZero();
            try (var raw = connection.getRabbitConnectionFactory().newConnection(); var channel = raw.createChannel()) {
                assertThat(channel.basicGet(namespace + ".run.q", true)).isNull();
            }
        } finally { unreachable.destroy(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void revokedQuestionCannotExecuteAfterBrokerRecoveryEvenWithAWarmCache(boolean unpublish) throws Exception {
        String questionId = "qb-java-001";
        int version = jdbc.queryForObject("SELECT version FROM question_bank WHERE id=?", Integer.class, questionId);
        snapshotKey = namespace + ":question:snapshot:s1:" + questionId + ":v" + version;
        var task = data(performPost("/api/interviews/training-tasks/from-source", Map.of(
                "requestId", UUID.randomUUID().toString(), "sourceType", "QUESTION",
                "questionId", questionId, "questionVersion", version), token));
        assertThat(cacheRedis.hasKey(snapshotKey)).isTrue();
        String requestId = UUID.randomUUID().toString();
        String path = "/api/interviews/training-tasks/" + task.get("id") + "/start-commands";
        var accepted = performPost(path, Map.of("requestId", requestId), token);
        assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
        String id = (String) data(accepted).get("runId");
        var deadline = store.get(owner, id).deadline();
        var unreachable = new CachingConnectionFactory("127.0.0.1", 1);
        unreachable.setConnectionTimeout(200);
        try {
            new RunDispatchPublisher(outbox, new org.springframework.amqp.rabbit.core.RabbitTemplate(unreachable),
                    objectMapper, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), "rabbitmq",
                    Integer.parseInt(env("BENCHMARK_PUBLISHER_BATCH_SIZE", "1")),
                    Long.parseLong(env("BENCHMARK_PUBLISHER_BATCH_BUDGET_MS", "100"))).publish();
            assertThat(jdbc.queryForObject("SELECT status FROM interview_run_outbox WHERE run_id=?", String.class, id))
                    .isEqualTo("PENDING");
            // 测试库没有题目编辑 HTTP API，直接提交下架或版本修订以模拟内容变更。
            if (unpublish) jdbc.update("UPDATE question_bank SET published=FALSE WHERE id=?", questionId);
            else jdbc.update("UPDATE question_bank SET version=version+1 WHERE id=?", questionId);
            jdbc.update("UPDATE interview_run_outbox SET next_attempt_at=CURRENT_TIMESTAMP(6) WHERE run_id=?", id);
            publisher.publish();
            try (var raw = connection.getRabbitConnectionFactory().newConnection()) {
                try (var channel = raw.createChannel()) {
                    var lostAck = (Channel) Proxy.newProxyInstance(Channel.class.getClassLoader(), new Class<?>[]{Channel.class},
                            (proxy, method, args) -> {
                                if (method.getName().equals("basicAck")) throw new IOException("lost ACK after source rejection");
                                return method.invoke(channel, args);
                            });
                    assertThatThrownBy(() -> consumer.receive(get(channel, false), lostAck)).isInstanceOf(IOException.class);
                }
                var rejected = store.get(owner, id);
                assertThat(rejected.status()).isEqualTo("FAILED");
                assertThat(rejected.attempts()).isZero();
                assertThat(rejected.deadline()).isEqualTo(deadline);
                assertThat(store.events(owner, id, 0)).noneMatch(event -> event.type().equals("turn.committed"));
                var quota = jdbc.queryForList("SELECT * FROM request_quotas ORDER BY scope_key,window_start");
                try (var channel = raw.createChannel()) { consumer.receive(get(channel, true), channel); }
                assertThat(store.get(owner, id)).isEqualTo(rejected);
                assertThat(jdbc.queryForList("SELECT * FROM request_quotas ORDER BY scope_key,window_start")).isEqualTo(quota);
                assertThat(data(performPost(path, Map.of("requestId", requestId), token)).get("runId")).isEqualTo(id);
            }
        } finally {
            unreachable.destroy();
            jdbc.update("UPDATE question_bank SET version=?,published=TRUE WHERE id=?", version, questionId);
        }
    }
}
