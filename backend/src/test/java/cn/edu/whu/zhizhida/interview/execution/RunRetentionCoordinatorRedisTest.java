package cn.edu.whu.zhizhida.interview.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 显式指定测试 Redis 端口后验证实际 Lua；仅清理本测试 UUID 命名空间中的两个键。 */
@EnabledIfEnvironmentVariable(named = "TEST_REDIS_PORT", matches = "[0-9]+")
class RunRetentionCoordinatorRedisTest {
    private final String namespace = "retention-test:" + UUID.randomUUID();
    private final String leaseKey = "{" + namespace + ":run-retention}:lease";
    private final String cursorKey = "{" + namespace + ":run-retention}:cursor";
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private RunRetentionCoordinator first;
    private RunRetentionCoordinator second;

    @BeforeEach
    void connect() {
        var configuration = new RedisStandaloneConfiguration(
                System.getenv().getOrDefault("TEST_REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(System.getenv("TEST_REDIS_PORT")));
        String password = System.getenv("TEST_REDIS_PASSWORD");
        if (password != null && !password.isEmpty()) configuration.setPassword(password);
        factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        first = new RunRetentionCoordinator(redis, namespace);
        second = new RunRetentionCoordinator(new StringRedisTemplate(factory), namespace);
    }

    @AfterEach
    void cleanup() {
        try {
            if (redis != null) redis.delete(List.of(leaseKey, cursorKey));
        } finally {
            if (factory != null) factory.destroy();
        }
    }

    @Test
    void clientsShareExclusiveLeaseAndDurableCursor() {
        var lease = first.acquire();
        assertThat(lease.cursor()).isEmpty();
        assertThat(second.acquire()).isNull();
        assertThat(redis.getExpire(leaseKey, TimeUnit.MILLISECONDS)).isBetween(1L, 30000L);
        assertThat(first.renew(lease)).isTrue();
        assertThat(first.advance(lease, "last-processed-run")).isTrue();
        first.release(lease);

        var resumed = second.acquire();
        assertThat(resumed.cursor()).isEqualTo("last-processed-run");
        assertThat(resumed.token()).isNotEqualTo(lease.token());
        assertThat(second.advance(resumed, "")).isTrue();
        second.release(resumed);
        assertThat(first.acquire().cursor()).isEmpty();
    }

    @Test
    void crashedOwnerExpiresAndCannotOverwriteCursorOrReleaseReplacement() {
        var stale = first.acquire();
        assertThat(first.advance(stale, "completed-page")).isTrue();
        redis.expire(leaseKey, Duration.ofMillis(20));
        await().atMost(Duration.ofSeconds(3)).until(() -> !redis.hasKey(leaseKey));

        var replacement = second.acquire();
        assertThat(replacement.cursor()).isEqualTo("completed-page");
        assertThat(first.renew(stale)).isFalse();
        assertThat(first.advance(stale, "unfinished-page")).isFalse();
        first.release(stale);
        assertThat(first.acquire()).isNull();
        assertThat(redis.opsForValue().get(cursorKey)).isEqualTo("completed-page");
        assertThat(second.renew(replacement)).isTrue();
    }

    @Test
    void simultaneousWorkersHaveExactlyOneWinner() throws Exception {
        var barrier = new CyclicBarrier(12);
        var pool = Executors.newFixedThreadPool(12);
        try {
            List<Future<RunRetentionCoordinator.Lease>> attempts = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                var coordinator = i % 2 == 0 ? first : second;
                attempts.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return coordinator.acquire();
                }));
            }
            int winners = 0;
            for (var attempt : attempts)
                if (attempt.get(5, TimeUnit.SECONDS) != null) winners++;
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
