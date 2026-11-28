package cn.edu.whu.zhizhida.middleware;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import cn.edu.whu.zhizhida.quota.QuotaExceededException;
import cn.edu.whu.zhizhida.quota.RedisSubmissionRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** 在两个独立客户端间验证 Redis 限流窗口，并用本轮专属的 RocketMQ 消费组验证消息重投。 */
@EnabledIfEnvironmentVariable(named = "RUN_MIDDLEWARE_TESTS", matches = "true")
class MiddlewareLiveTest {
    static String env(String key, String fallback) {
        return System.getenv().getOrDefault(key, fallback);
    }

    LettuceConnectionFactory redisFactory() {
        var config = new RedisStandaloneConfiguration(
                env("REDIS_RATE_HOST", env("REDIS_HOST", "127.0.0.1")),
                Integer.parseInt(env("REDIS_RATE_PORT", env("REDIS_PORT", "6379"))));
        String password = env("REDIS_RATE_PASSWORD", env("REDIS_PASSWORD", ""));
        if (!password.isEmpty())
            config.setPassword(password);
        var factory = new LettuceConnectionFactory(
                config,
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofSeconds(2))
                        .build());
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    @Test
    void twoIndependentClientsShareOneAtomicWindowAndDoNotExtendTtl() throws Exception {
        var first = redisFactory();
        var second = redisFactory();
        var one = new StringRedisTemplate(first);
        var two = new StringRedisTemplate(second);
        String namespace = "zhizhida:test:" + UUID.randomUUID();
        var keys = List.of(namespace + ":rate:ai-submit:42", namespace + ":rate:ai-submit:43");
        try (var pool = Executors.newFixedThreadPool(8)) {
            var a = new RedisSubmissionRateLimiter(
                    one, new SimpleMeterRegistry(), namespace, 10, 60000);
            var b = new RedisSubmissionRateLimiter(
                    two, new SimpleMeterRegistry(), namespace, 10, 60000);
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 40; i++) {
                var client = i % 2 == 0 ? a : b;
                futures.add(
                        pool.submit(
                                () -> {
                                    try {
                                        client.require(42);
                                        return true;
                                    } catch (QuotaExceededException ex) {
                                        return false;
                                    }
                                }));
            }
            int accepted = 0;
            for (var future : futures)
                if (future.get(10, TimeUnit.SECONDS))
                    accepted++;
            assertThat(accepted).isEqualTo(10);
            assertThat(one.opsForValue().get(keys.getFirst())).isEqualTo("10");
            long ttl = one.getExpire(keys.getFirst(), TimeUnit.MILLISECONDS);
            assertThat(ttl).isBetween(1L, 60000L);
            assertThatThrownBy(() -> a.require(42)).isInstanceOf(QuotaExceededException.class);
            assertThat(two.getExpire(keys.getFirst(), TimeUnit.MILLISECONDS))
                    .isLessThanOrEqualTo(ttl);
            b.require(43);
            one.opsForValue().set(keys.get(1), "9");
            a.require(43);
            assertThat(two.opsForValue().get(keys.get(1))).isEqualTo("10");
            assertThat(two.getExpire(keys.get(1), TimeUnit.MILLISECONDS)).isBetween(1L, 60000L);
            one.persist(keys.get(1));
            assertThatThrownBy(() -> b.require(43)).isInstanceOf(QuotaExceededException.class);
            assertThat(one.getExpire(keys.get(1), TimeUnit.MILLISECONDS)).isBetween(1L, 60000L);
        } finally {
            try {
                one.delete(keys);
            } finally {
                first.destroy();
                second.destroy();
            }
        }
    }

    @Test
    void brokerConfirmsSendAndRedeliversUnacknowledgedWork() throws Exception {
        String topic = TestBroker.name("run-ready");
        String key = UUID.randomUUID().toString();
        try (var client = TestBroker.open();
                var producer = TestBroker.SDK
                        .newProducerBuilder()
                        .setClientConfiguration(TestBroker.config())
                        .setTopics(topic)
                        .setMaxAttempts(1)
                        .build()) {
            client.receive(1, Duration.ofSeconds(12));
            var message = TestBroker.SDK
                    .newMessageBuilder()
                    .setTopic(topic)
                    .setKeys(key)
                    .setBody(new byte[] { 42 })
                    .build();
            var receipt = producer.send(message);
            var first = TestBroker.receive(client, "run-ready");
            assertThat(first.getMessageId().toString())
                    .isEqualTo(receipt.getMessageId().toString());
            client.changeInvisibleDuration(first, Duration.ofSeconds(10));
            var again = TestBroker.receive(client, "run-ready");
            assertThat(again.getKeys()).containsExactly(key);
            assertThat(again.getDeliveryAttempt()).isGreaterThan(first.getDeliveryAttempt());
            client.ack(again);
            assertThatThrownBy(
                    () -> producer.send(
                            TestBroker.SDK
                                    .newMessageBuilder()
                                    .setTopic(TestBroker.name("missing-topic"))
                                    .setKeys(key)
                                    .setBody(new byte[] { 42 })
                                    .build()))
                    .isInstanceOf(org.apache.rocketmq.client.apis.ClientException.class);
        }
    }
}
