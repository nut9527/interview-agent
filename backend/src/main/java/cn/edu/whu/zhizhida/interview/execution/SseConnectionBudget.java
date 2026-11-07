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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import cn.edu.whu.zhizhida.common.BusinessException;

/** 集群共享的 SSE 连接预算；连接最多存活两分钟，崩溃实例的许可自动到期。 */
@Component
public class SseConnectionBudget {
    static final long CONNECTION_MILLIS = 120_000;
    // 先关闭本地连接，再让 Redis 回收预算，预留网络/调度延迟。
    static final long LEASE_MILLIS = CONNECTION_MILLIS + 10_000;
    private static final Logger log = LoggerFactory.getLogger(SseConnectionBudget.class);
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "return redis.call('ZREM', KEYS[1], ARGV[1]) + redis.call('ZREM', KEYS[2], ARGV[1])", Long.class);
    static {
        ACQUIRE.setLocation(new ClassPathResource("redis/sse-acquire.lua"));
        ACQUIRE.setResultType(Long.class);
    }
    private final StringRedisTemplate redis;
    private final String prefix;
    private final int maximum;
    private final int perOwner;

    public SseConnectionBudget(@Qualifier("submissionRedisTemplate") StringRedisTemplate redis,
            @Value("${app.redis.namespace:zhizhida:dev}") String namespace,
            @Value("${interview.runs.events.max-connections:64}") int maximum,
            @Value("${interview.runs.events.max-connections-per-owner:3}") int perOwner) {
        if (maximum < 1 || perOwner < 1 || perOwner > maximum)
            throw new IllegalArgumentException("SSE connection limits must be positive and owner limit <= total");
        this.redis = redis;
        this.prefix = namespace + ":{sse}:";
        this.maximum = maximum;
        this.perOwner = perOwner;
    }

    public String acquire(long owner) {
        String token = UUID.randomUUID().toString();
        Long acquired;
        try {
            acquired = redis.execute(ACQUIRE, keys(owner), token, Integer.toString(maximum),
                    Integer.toString(perOwner), Long.toString(LEASE_MILLIS));
        } catch (RuntimeException ex) {
            throw unavailable();
        }
        if (Long.valueOf(0).equals(acquired))
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "进度连接过多，请关闭其他页面后重试。");
        if (!Long.valueOf(1).equals(acquired)) throw unavailable();
        return token;
    }

    public void release(long owner, String token) {
        try { redis.execute(RELEASE, keys(owner), token); }
        catch (RuntimeException ex) {
            // 释放失败不能阻断其他连接关闭；TTL 最终回收，禁止降级为本地配额。
            log.warn("SSE permit release unavailable; lease will expire");
        }
    }

    private List<String> keys(long owner) { return List.of(prefix + "all", prefix + "owner:" + owner); }
    private static BusinessException unavailable() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "进度连接配额服务暂不可用，请稍后重试。");
    }
}
