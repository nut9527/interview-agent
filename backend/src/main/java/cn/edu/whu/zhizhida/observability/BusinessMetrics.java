package cn.edu.whu.zhizhida.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** 指标仅使用有限枚举标签，不附带用户、请求编号、提示词或岗位编号。 */
@Component
public class BusinessMetrics {
    private static final BusinessMetrics DISABLED = new BusinessMetrics(
            new io.micrometer.core.instrument.composite.CompositeMeterRegistry());
    /** AI 操作仅表示模型协议与 JSON 解析结果，各业务能力单独记录业务执行结果。 */
    public enum Operation { AI, AI_HTTP, RESUME_OPTIMIZE, INTERVIEW_OPENING, INTERVIEW_REVIEW, INTERVIEW_REPAIR, INTERVIEW_OPENING_REPAIR,
        INTERVIEW_ANSWER, QUOTA }
    public enum Outcome { SUCCESS, FAILURE, DEGRADED, REJECTED, CONFLICT }
    private final MeterRegistry registry;

    public BusinessMetrics(MeterRegistry registry) { this.registry = registry; }

    public static BusinessMetrics disabled() { return DISABLED; }

    public <T> T measure(Operation operation, java.util.function.Supplier<T> call) {
        long started = System.nanoTime();
        try {
            T result = call.get();
            record(operation, Outcome.SUCCESS, System.nanoTime() - started);
            return result;
        } catch (RuntimeException ex) {
            record(operation, Outcome.FAILURE, System.nanoTime() - started);
            throw ex;
        }
    }

    public void modelUsage(cn.edu.whu.zhizhida.ai.api.ModelUsage usage) {
        registry.counter("zhizhida.ai.usage", "status", usage.status().name().toLowerCase(java.util.Locale.ROOT)).increment();
        if (usage.status() == cn.edu.whu.zhizhida.ai.api.ModelUsage.Status.REPORTED) {
            registry.counter("zhizhida.ai.tokens", "kind", "input").increment(usage.inputTokens());
            registry.counter("zhizhida.ai.tokens", "kind", "output").increment(usage.outputTokens());
            registry.counter("zhizhida.ai.tokens", "kind", "total").increment(usage.totalTokens());
        }
    }

    public void record(Operation operation, Outcome outcome, long nanos) {
        registry.timer("zhizhida.business", "operation", operation.name().toLowerCase(java.util.Locale.ROOT),
                "outcome", outcome.name().toLowerCase(java.util.Locale.ROOT))
                .record(Math.max(0, nanos), TimeUnit.NANOSECONDS);
    }
}
