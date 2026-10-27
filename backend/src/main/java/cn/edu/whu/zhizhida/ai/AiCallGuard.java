package cn.edu.whu.zhizhida.ai;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;

/** 单实例共享的 AI 容量保护：立即拒绝过载，连续失败后熔断，冷却后只放行一次探测。 */
@Component
public class AiCallGuard {
    private static final Logger log = LoggerFactory.getLogger(AiCallGuard.class);
    private final Semaphore capacity;
    private final int failureThreshold;
    private final long cooldownNanos;
    private final LongSupplier nanoTime;
    private final BusinessMetrics metrics;
    private int failures;
    // 每次熔断或探测恢复均推进代次，旧请求的完成结果不能改变新一轮熔断状态。
    private long generation;
    private long openedAt;
    private boolean open;
    private boolean probing;

    public AiCallGuard(int maxConcurrentCalls, int failureThreshold, long openSeconds) {
        this(maxConcurrentCalls, failureThreshold, Duration.ofSeconds(openSeconds), System::nanoTime,
                BusinessMetrics.disabled());
    }

    @Autowired
    public AiCallGuard(@Value("${ai.resilience.max-concurrent-calls:4}") int maxConcurrentCalls,
            @Value("${ai.resilience.failure-threshold:3}") int failureThreshold,
            @Value("${ai.resilience.open-seconds:30}") long openSeconds, BusinessMetrics metrics) {
        this(maxConcurrentCalls, failureThreshold, Duration.ofSeconds(openSeconds), System::nanoTime, metrics);
    }

    AiCallGuard(int maxConcurrentCalls, int failureThreshold, Duration cooldown, LongSupplier nanoTime) {
        this(maxConcurrentCalls, failureThreshold, cooldown, nanoTime, BusinessMetrics.disabled());
    }

    AiCallGuard(int maxConcurrentCalls, int failureThreshold, Duration cooldown, LongSupplier nanoTime,
            BusinessMetrics metrics) {
        if (maxConcurrentCalls < 1 || failureThreshold < 1 || cooldown.isNegative() || cooldown.isZero()) {
            throw new IllegalArgumentException("AI 容量、失败阈值和熔断冷却时间必须大于 0");
        }
        this.capacity = new Semaphore(maxConcurrentCalls);
        this.failureThreshold = failureThreshold;
        this.cooldownNanos = cooldown.toNanos();
        this.nanoTime = nanoTime;
        this.metrics = metrics;
    }

    public <T> T execute(Supplier<T> call) {
        long started = System.nanoTime();
        // 不排队等待许可，立即返回繁忙，避免上游变慢时请求持续占用业务线程。
        if (!capacity.tryAcquire()) {
            record(BusinessMetrics.Outcome.REJECTED, started);
            throw unavailable("AI 服务繁忙，请稍后重试。");
        }
        try {
            long ticket;
            try {
                ticket = admit();
            } catch (BusinessException ex) {
                record(BusinessMetrics.Outcome.REJECTED, started);
                throw ex;
            }
            try {
                T result = call.get();
                succeeded(ticket);
                record(BusinessMetrics.Outcome.SUCCESS, started);
                return result;
            } catch (RuntimeException ex) {
                failed(ticket);
                record(BusinessMetrics.Outcome.FAILURE, started);
                throw ex;
            }
        } finally {
            capacity.release();
        }
    }

    private void record(BusinessMetrics.Outcome outcome, long started) {
        metrics.record(BusinessMetrics.Operation.AI, outcome, System.nanoTime() - started);
    }

    private synchronized long admit() {
        if (open) {
            if (probing || nanoTime.getAsLong() - openedAt < cooldownNanos) {
                throw unavailable("AI 服务暂时不可用，请稍后重试。");
            }
            probing = true;
        }
        return generation;
    }

    private synchronized void succeeded(long ticket) {
        if (ticket != generation) {
            return; // 熔断前尚未结束的请求不能关闭新一轮熔断。
        }
        failures = 0;
        if (probing) {
            open = false;
            probing = false;
            generation++;
            log.info("AI circuit recovered");
        }
    }

    private synchronized void failed(long ticket) {
        if (ticket != generation) {
            return;
        }
        if (probing || ++failures >= failureThreshold) {
            open = true;
            probing = false;
            openedAt = nanoTime.getAsLong();
            generation++;
            log.warn("AI circuit opened; cooldownMs={}", cooldownNanos / 1_000_000);
        }
    }

    private BusinessException unavailable(String message) {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
