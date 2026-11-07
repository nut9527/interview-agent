package cn.edu.whu.zhizhida.ai;

import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import static org.assertj.core.api.Assertions.*;

/** 两个独立客户端执行正式 Lua；只清理本次 UUID 命名空间，不执行 FLUSHDB。 */
@EnabledIfEnvironmentVariable(named = "RUN_MIDDLEWARE_TESTS", matches = "true")
class AiCallGuardRedisTest {
    final String namespace = "zhizhida:test:" + UUID.randomUUID();
    final List<String> keys = List.of(namespace + ":{ai-call}:leases", namespace + ":{ai-call}:circuit");
    final DefaultRedisScript<List> acquire = script("ai-call-acquire.lua", List.class);
    final DefaultRedisScript<Long> settle = script("ai-call-settle.lua", Long.class);
    LettuceConnectionFactory first;
    LettuceConnectionFactory second;
    StringRedisTemplate one;
    StringRedisTemplate two;

    @BeforeEach void connect() {
        first = factory();
        second = factory();
        one = new StringRedisTemplate(first);
        two = new StringRedisTemplate(second);
    }

    @AfterEach void cleanup() {
        try { if (one != null) one.delete(keys); }
        finally {
            if (first != null) first.destroy();
            if (second != null) second.destroy();
        }
    }

    @Test void competingInstancesNeverExceedSharedCapacityAndExpiredOwnersAreRecovered() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<java.util.concurrent.Future<List<?>>>();
            for (int i = 0; i < 40; i++) {
                var client = i % 2 == 0 ? one : two;
                results.add(pool.submit(() -> admit(client, UUID.randomUUID().toString(), 4, 60000)));
            }
            int accepted = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS).getFirst().equals(1L)) accepted++;
            assertThat(accepted).isEqualTo(4);
            assertThat(one.opsForZSet().size(keys.getFirst())).isEqualTo(4);
            assertThat(one.getExpire(keys.getFirst(), TimeUnit.MILLISECONDS)).isBetween(1L, 60000L);
            // 模拟进程崩溃、租约到期，无需按测试机时间构造脚本时间或等待一分钟。
            for (var token : one.opsForZSet().range(keys.getFirst(), 0, -1)) {
                one.opsForZSet().add(keys.getFirst(), token, 0);
            }
            assertThat(admit(two, "replacement", 4, 60000).getFirst()).isEqualTo(1L);
            assertThat(one.opsForZSet().size(keys.getFirst())).isEqualTo(1);
        }
    }

    @Test void oneInstanceCannotBypassAnotherInstancesCapacity() {
        var a = guard(one, 1, 3);
        var b = guard(two, 1, 3);
        assertThat(a.execute(Duration.ofSeconds(25), () -> {
            unavailable(b);
            return "first";
        })).isEqualTo("first");
        assertThat(b.execute(Duration.ofSeconds(25), () -> "released")).isEqualTo("released");
        assertThat(one.opsForZSet().size(keys.getFirst())).isZero();
    }

    @Test void sharedCircuitCountsConsecutiveFailuresAndOnlyOneInstanceCanProbe() {
        var a = guard(one, 3, 2);
        var b = guard(two, 3, 2);
        fail(a);
        assertThat(b.execute(Duration.ofSeconds(25), () -> "reset failures")).isEqualTo("reset failures");
        fail(a);
        fail(b);
        unavailable(a);
        unavailable(b);
        one.opsForHash().put(keys.get(1), "open_until", "1");
        assertThat(a.execute(Duration.ofSeconds(25), () -> {
            unavailable(b);
            return "probe";
        })).isEqualTo("probe");
        assertThat(b.execute(Duration.ofSeconds(25), () -> "recovered")).isEqualTo("recovered");
        assertThat(one.opsForHash().get(keys.get(1), "open_until")).isEqualTo("0");
    }

    @Test void staleResultsCannotCloseNewCircuitOrSettleAReplacementProbe() {
        long old = generation(admit(one, "old", 3, 60000));
        long failing = generation(admit(two, "failing", 3, 60000));
        finish(two, "failing", failing, false, 1);
        assertThat(finish(one, "old", old, true, 1)).isZero();
        assertThat(admit(one, "blocked", 3, 60000).getFirst()).isEqualTo(-1L);
        one.opsForHash().put(keys.get(1), "open_until", "1");
        long abandoned = generation(admit(one, "abandoned-probe", 3, 60000));
        assertThat(admit(two, "blocked-probe", 3, 60000).getFirst()).isEqualTo(-1L);
        one.opsForZSet().add(keys.getFirst(), "abandoned-probe", 0);
        long replacement = generation(admit(two, "replacement-probe", 3, 60000));
        assertThat(replacement).isGreaterThan(abandoned);
        assertThat(finish(one, "abandoned-probe", abandoned, true, 1)).isZero();
        assertThat(one.opsForHash().get(keys.get(1), "probe")).isEqualTo("replacement-probe");
        assertThat(finish(two, "replacement-probe", replacement, true, 1)).isEqualTo(1);
        assertThat(admit(one, "normal", 3, 60000).getFirst()).isEqualTo(1L);
    }

    @Test void failedProbeRestartsCooldownAndDuplicateSettlementCannotCountTwice() {
        long ticket = generation(admit(one, "failure", 1, 60000));
        finish(one, "failure", ticket, false, 1);
        one.opsForHash().put(keys.get(1), "open_until", "1");
        long probe = generation(admit(two, "probe", 1, 60000));
        finish(two, "probe", probe, false, 1);
        assertThat(finish(two, "probe", probe, false, 1)).isZero();
        assertThat(one.opsForHash().get(keys.get(1), "failures")).isEqualTo("2");
        assertThat(admit(one, "too-soon", 1, 60000).getFirst()).isEqualTo(-1L);
        assertThat(one.opsForZSet().size(keys.getFirst())).isZero();
    }

    List<?> admit(StringRedisTemplate client, String token, int capacity, long leaseMillis) {
        return client.execute(acquire, keys, token, Integer.toString(capacity), Long.toString(leaseMillis));
    }

    long generation(List<?> result) {
        assertThat(result.getFirst()).isEqualTo(1L);
        return (Long) result.get(1);
    }

    Long finish(StringRedisTemplate client, String token, long generation, boolean success, int threshold) {
        return client.execute(settle, keys, token, Long.toString(generation), success ? "1" : "0",
                Integer.toString(threshold), "30000");
    }

    AiCallGuard guard(StringRedisTemplate client, int capacity, int threshold) {
        return new AiCallGuard(client, namespace, capacity, threshold, 30, BusinessMetrics.disabled());
    }

    void fail(AiCallGuard guard) {
        assertThatThrownBy(() -> guard.execute(Duration.ofSeconds(25), () -> { throw new IllegalStateException("provider"); }))
                .isInstanceOf(IllegalStateException.class);
    }

    void unavailable(AiCallGuard guard) {
        assertThatThrownBy(() -> guard.execute(Duration.ofSeconds(25), () -> { throw new AssertionError("must not run"); }))
                .isInstanceOf(BusinessException.class);
    }

    static <T> DefaultRedisScript<T> script(String name, Class<T> resultType) {
        var script = new DefaultRedisScript<T>();
        script.setLocation(new ClassPathResource("redis/" + name));
        script.setResultType(resultType);
        return script;
    }

    LettuceConnectionFactory factory() {
        var config = new RedisStandaloneConfiguration(env("REDIS_RATE_HOST", env("REDIS_HOST", "127.0.0.1")),
                Integer.parseInt(env("REDIS_RATE_PORT", env("REDIS_PORT", "6379"))));
        String password = env("REDIS_RATE_PASSWORD", env("REDIS_PASSWORD", ""));
        if (!password.isEmpty()) config.setPassword(password);
        var factory = new LettuceConnectionFactory(config, LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(2)).shutdownTimeout(Duration.ofMillis(100)).build());
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    static String env(String name, String fallback) { return System.getenv().getOrDefault(name, fallback); }
}
