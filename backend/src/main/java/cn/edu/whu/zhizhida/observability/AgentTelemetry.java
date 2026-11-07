package cn.edu.whu.zhizhida.observability;

import cn.edu.whu.zhizhida.ai.api.ModelUsage;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/** 追踪只记录元数据；追踪设施异常降级或忽略，不能覆盖业务结果与原始异常。 */
@Component
public final class AgentTelemetry {
    public enum Stage {
        ACCEPT,
        RUN,
        MODEL,
        TOOL,
        VALIDATE,
        COMMIT
    }

    /** 仅标识符和采样决定跨越持久边界，不传递 baggage。 */
    public record Parent(String traceId, String spanId, boolean sampled) {
        public boolean valid() {
            return traceId != null
                    && traceId.matches("[0-9a-f]{32}")
                    && !traceId.equals("0".repeat(32))
                    && spanId != null
                    && spanId.matches("[0-9a-f]{16}")
                    && !spanId.equals("0".repeat(16));
        }
    }

    private final Tracer tracer;

    @Autowired
    public AgentTelemetry(ObjectProvider<Tracer> tracers) {
        this.tracer = tracers.getIfAvailable(() -> Tracer.NOOP);
    }

    public AgentTelemetry(Tracer tracer) {
        this.tracer = java.util.Objects.requireNonNull(tracer);
    }

    public Scope start(Stage stage) {
        return start(stage, null, false);
    }

    /** 恢复持久 Run 时忽略调度器、MQ 和调用方原有的追踪上下文。 */
    public Scope resume(Parent parent) {
        return start(Stage.RUN, parent, true);
    }

    private Scope start(Stage stage, Parent parent, boolean detached) {
        Span span = null;
        try {
            var builder =
                    tracer.spanBuilder()
                            .name("agent." + stage.name().toLowerCase(java.util.Locale.ROOT));
            if (detached) {
                if (parent != null && parent.valid())
                    builder.setParent(
                            tracer.traceContextBuilder()
                                    .traceId(parent.traceId())
                                    .spanId(parent.spanId())
                                    .sampled(parent.sampled())
                                    .build());
                else builder.setNoParent();
            }
            span = builder.start();
            return new Scope(span, tracer.withSpan(span));
        } catch (RuntimeException ignored) {
            if (span != null) {
                try {
                    span.end();
                } catch (RuntimeException unavailable) {
                }
            }
            return new Scope(Span.NOOP, () -> {});
        }
    }

    /** 仅向有界工具回调传递追踪上下文，不传递身份或事务 ThreadLocal。 */
    public <T> Callable<T> wrap(Callable<T> callback) {
        Span parent;
        try {
            parent = tracer.currentSpan();
        } catch (RuntimeException ignored) {
            return callback;
        }
        if (parent == null) return callback;
        return () -> {
            Tracer.SpanInScope scope;
            try {
                scope = tracer.withSpan(parent);
            } catch (RuntimeException ignored) {
                return callback.call();
            }
            try {
                return callback.call();
            } finally {
                try {
                    scope.close();
                } catch (RuntimeException ignored) {
                }
            }
        };
    }

    public <T> T observe(Stage stage, Supplier<T> action) {
        try (var scope = start(stage)) {
            try {
                return action.get();
            } catch (RuntimeException ex) {
                scope.failure("STAGE_FAILED");
                throw ex;
            }
        }
    }

    public static final class Scope implements AutoCloseable {
        private static final tools.jackson.databind.ObjectMapper USAGE_JSON =
                new tools.jackson.databind.json.JsonMapper();
        private final Span span;
        private final Tracer.SpanInScope current;
        private boolean closed;

        private Scope(Span span, Tracer.SpanInScope current) {
            this.span = span;
            this.current = current;
        }

        public Parent parent() {
            try {
                var context = span.context();
                var parent =
                        new Parent(
                                context.traceId(),
                                context.spanId(),
                                Boolean.TRUE.equals(context.sampled()));
                return parent.valid() ? parent : null;
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        /** 调用方仅提供获准元数据，不传入原始异常消息或模型生成的标识符。 */
        public Scope tag(String key, String value) {
            if (value != null && value.matches("[A-Za-z0-9_.:/-]{1,128}")) {
                try {
                    span.tag(key, value);
                } catch (RuntimeException ignored) {
                }
            }
            return this;
        }

        public void usage(ModelUsage usage) {
            tag("agent.usage.status", usage.status().name());
            if (usage.status() == ModelUsage.Status.REPORTED) {
                tag("gen_ai.usage.input_tokens", usage.inputTokens().toString());
                tag("gen_ai.usage.output_tokens", usage.outputTokens().toString());
            }
        }

        public void usage(tools.jackson.databind.JsonNode usage) {
            try {
                usage(USAGE_JSON.treeToValue(usage, ModelUsage.class));
            } catch (RuntimeException ignored) {
                tag("agent.usage.status", "INVALID");
            }
        }

        public void failure(String code) {
            String safe =
                    code != null && code.matches("[A-Z][A-Z0-9_]{0,63}") ? code : "STAGE_FAILED";
            tag("error.type", safe);
            // 不导出原异常、原因或堆栈，防止泄露材料正文或密钥。
            try {
                span.error(new SafeFailure(safe));
            } catch (RuntimeException ignored) {
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            try {
                current.close();
            } catch (RuntimeException ignored) {
            }
            try {
                span.end();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static final class SafeFailure extends RuntimeException {
        SafeFailure(String code) {
            super(code, null, false, false);
        }
    }
}
