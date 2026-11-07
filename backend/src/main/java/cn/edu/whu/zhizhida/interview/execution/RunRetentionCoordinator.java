package cn.edu.whu.zhizhida.interview.execution;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** 跨 Worker 共享清理租约和扫描游标；过期持有者不能推进游标或释放新持有者。 */
@Component
final class RunRetentionCoordinator {
    private static final Logger log = LoggerFactory.getLogger(RunRetentionCoordinator.class);
    private static final String LEASE_MILLIS = "30000";
    private static final DefaultRedisScript<String> ACQUIRE = script("retention-acquire", String.class);
    private static final DefaultRedisScript<Long> RENEW = script("retention-renew", Long.class);
    private static final DefaultRedisScript<Long> ADVANCE = script("retention-advance", Long.class);
    private static final DefaultRedisScript<Long> RELEASE = script("retention-release", Long.class);

    record Lease(String token, String cursor) {}

    private final StringRedisTemplate redis;
    private final List<String> keys;

    RunRetentionCoordinator(@Qualifier("submissionRedisTemplate") StringRedisTemplate redis,
            @Value("${app.redis.namespace:zhizhida:dev}") String namespace) {
        if (namespace.isBlank() || namespace.contains("{") || namespace.contains("}"))
            throw new IllegalArgumentException("Invalid Redis namespace");
        this.redis = redis;
        String prefix = "{" + namespace + ":run-retention}:";
        keys = List.of(prefix + "lease", prefix + "cursor");
    }

    Lease acquire() {
        String token = UUID.randomUUID().toString();
        String cursor = redis.execute(ACQUIRE, keys, token, LEASE_MILLIS);
        return cursor == null ? null : new Lease(token, cursor);
    }

    boolean renew(Lease lease) {
        return Long.valueOf(1).equals(redis.execute(RENEW, List.of(keys.getFirst()), lease.token(), LEASE_MILLIS));
    }

    boolean advance(Lease lease, String cursor) {
        return Long.valueOf(1).equals(redis.execute(ADVANCE, keys, lease.token(), cursor));
    }

    void release(Lease lease) {
        try {
            redis.execute(RELEASE, List.of(keys.getFirst()), lease.token());
        } catch (RuntimeException ex) {
            // 过期时间会回收租约；释放失败不能掩盖清理异常或绕过后续持有者校验。
            log.warn("Run retention lease release failed; waiting for lease expiry");
        }
    }

    private static <T> DefaultRedisScript<T> script(String name, Class<T> result) {
        var script = new DefaultRedisScript<T>();
        script.setLocation(new ClassPathResource("redis/" + name + ".lua"));
        script.setResultType(result);
        return script;
    }
}
