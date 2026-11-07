package cn.edu.whu.zhizhida.interview.execution;

import static org.assertj.core.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import cn.edu.whu.zhizhida.common.BusinessException;

@EnabledIfEnvironmentVariable(named = "TEST_REDIS_PORT", matches = "\\d+")
class SseConnectionBudgetRedisTest {
    final String namespace = "test:sse:" + UUID.randomUUID();
    LettuceConnectionFactory firstFactory, secondFactory;
    StringRedisTemplate redis;
    SseConnectionBudget first, second;

    @BeforeEach void connect() {
        firstFactory = factory(); secondFactory = factory();
        redis = new StringRedisTemplate(firstFactory);
        first = new SseConnectionBudget(redis, namespace, 8, 3);
        second = new SseConnectionBudget(new StringRedisTemplate(secondFactory), namespace, 8, 3);
    }
    private LettuceConnectionFactory factory() {
        var factory = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getenv("TEST_REDIS_PORT")));
        factory.afterPropertiesSet(); factory.start(); return factory;
    }
    @AfterEach void cleanup() {
        var keys = new ArrayList<String>(); keys.add(namespace + ":{sse}:all");
        for (long i = 1; i <= 12; i++) keys.add(namespace + ":{sse}:owner:" + i);
        try { redis.delete(keys); } finally { firstFactory.destroy(); secondFactory.destroy(); }
    }
    @Test void ownerLimitReleaseAndExpiredLeasesWorkAcrossClients() {
        String old = first.acquire(1);
        second.acquire(1); first.acquire(1);
        assertFull(second, 1);
        second.release(1, old);
        String replacement = first.acquire(1);
        first.release(1, old);
        assertFull(second, 1);
        // 模拟实例崩溃后的过期记录；Lua 使用 Redis 时间回收两个维度的许可。
        redis.opsForZSet().add(namespace + ":{sse}:all", replacement, 1);
        redis.opsForZSet().add(namespace + ":{sse}:owner:1", replacement, 1);
        assertThat(second.acquire(1)).isNotBlank();
        assertFull(first, 1);
    }
    @Test void concurrentClientsCannotMultiplyGlobalBudget() throws Exception {
        var barrier = new CyclicBarrier(12);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 1; i <= 12; i++) {
                long owner = i; var budget = i % 2 == 0 ? first : second;
                results.add(callers.submit(() -> {
                    barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    try { budget.acquire(owner); return true; }
                    catch (BusinessException ex) { assertThat(ex.status().value()).isEqualTo(429); return false; }
                }));
            }
            int accepted = 0;
            for (var result : results) if (result.get(10, java.util.concurrent.TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(8);
        }
    }
    private void assertFull(SseConnectionBudget budget, long owner) {
        assertThatThrownBy(() -> budget.acquire(owner)).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.status().value()).isEqualTo(429));
    }
}
