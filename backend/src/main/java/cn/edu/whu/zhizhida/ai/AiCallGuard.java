package cn.edu.whu.zhizhida.ai;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

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
import cn.edu.whu.zhizhida.observability.BusinessMetrics;

/** 同一 Redis 命名空间内共享 AI 并发及熔断状态；Redis 故障时拒绝新调用。 */
@Component
public class AiCallGuard {
    private static final Logger log = LoggerFactory.getLogger(AiCallGuard.class);
    private static final DefaultRedisScript<List> ACQUIRE = script("ai-call-acquire.lua", List.class);
    private static final DefaultRedisScript<Long> SETTLE = script("ai-call-settle.lua", Long.class);
    private final StringRedisTemplate redis;
    private final List<String> keys;
    private final int capacity;
    private final int failureThreshold;
    private final long cooldownMillis;
    private final BusinessMetrics metrics;

    public AiCallGuard(@Qualifier("submissionRedisTemplate") StringRedisTemplate redis,
            @Value("${app.redis.namespace:zhizhida:dev}") String namespace,
            @Value("${ai.resilience.max-concurrent-calls:4}") int maxConcurrentCalls,
            @Value("${ai.resilience.failure-threshold:3}") int failureThreshold,
            @Value("${ai.resilience.open-seconds:30}") long openSeconds, BusinessMetrics metrics) {
        if (maxConcurrentCalls < 1 || failureThreshold < 1 || openSeconds < 1 || namespace.isBlank()) {
            throw new IllegalArgumentException("AI 容量、失败阈值和熔断冷却时间必须大于 0，命名空间不能为空");
        }
        this.redis = redis;
        // 两个键使用同一 hash tag，脚本也可用于 Redis Cluster。
        String prefix = namespace + ":{ai-call}:";
        this.keys = List.of(prefix + "leases", prefix + "circuit");
        this.capacity = maxConcurrentCalls;
        this.failureThreshold = failureThreshold;
        this.cooldownMillis = Duration.ofSeconds(openSeconds).toMillis();
        this.metrics = metrics;
    }

    /** 调用方必须在 requestTimeout 内终止实际 IO；多留 30 秒用于序列化和结算。 */
    public <T> T execute(Duration requestTimeout, Supplier<T> call) {
        if (requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("AI 请求超时必须大于 0");
        }
        long leaseMillis = requestTimeout.plusSeconds(30).toMillis();
        long started = System.nanoTime();
        String token = UUID.randomUUID().toString();
        long generation;
        try {
            generation = admit(token, leaseMillis);
        } catch (BusinessException ex) {
            record(BusinessMetrics.Outcome.REJECTED, started);
            throw ex;
        }
        T result;
        try {
            result = call.get();
        } catch (RuntimeException | Error ex) {
            // 结算/指标故障不能覆盖供应商原始错误，也不能让一个探测永久占用容量。
            try {
                settle(token, generation, false);
            } catch (RuntimeException | Error failure) {
                if (failure != ex) ex.addSuppressed(failure);
            }
            try {
                record(BusinessMetrics.Outcome.FAILURE, started);
            } catch (RuntimeException | Error failure) {
                if (failure != ex) ex.addSuppressed(failure);
            }
            throw ex;
        }
        // 供应商调用已完成；Redis 暂时不可用时由租约回收，避免重试已成功的付费调用。
        try {
            settle(token, generation, true);
        } catch (RuntimeException ex) {
            log.warn("AI guard settlement unavailable; permit will expire");
        }
        record(BusinessMetrics.Outcome.SUCCESS, started);
        return result;
    }

    private long admit(String token, long leaseMillis) {
        List<?> result;
        try {
            result = redis.execute(ACQUIRE, keys, token, Integer.toString(capacity), Long.toString(leaseMillis));
            if (result == null || result.size() != 2 || !(result.get(0) instanceof Long status)
                    || !(result.get(1) instanceof Long generation) || generation < 0
                    || (status != 1 && status != 0 && status != -1)) {
                throw new IllegalStateException("Invalid AI guard admission result");
            }
        } catch (RuntimeException ex) {
            throw unavailable("AI 请求保护服务暂时不可用，请稍后重试。");
        }
        if ((Long) result.get(0) != 1) {
            throw unavailable((Long) result.get(0) == 0 ? "AI 服务繁忙，请稍后重试。" : "AI 服务暂时不可用，请稍后重试。");
        }
        return (Long) result.get(1);
    }

    private void settle(String token, long generation, boolean success) {
        Long result = redis.execute(SETTLE, keys, token, Long.toString(generation), success ? "1" : "0",
                Integer.toString(failureThreshold), Long.toString(cooldownMillis));
        if (result == null) throw new IllegalStateException("Missing AI guard settlement result");
    }

    private void record(BusinessMetrics.Outcome outcome, long started) {
        metrics.record(BusinessMetrics.Operation.AI, outcome, System.nanoTime() - started);
    }

    private static <T> DefaultRedisScript<T> script(String name, Class<T> type) {
        var script = new DefaultRedisScript<T>();
        script.setLocation(new ClassPathResource("redis/" + name));
        script.setResultType(type);
        return script;
    }

    private BusinessException unavailable(String message) {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
