package cn.edu.whu.zhizhida.observability;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import cn.edu.whu.zhizhida.ai.api.ModelUsage;
import cn.edu.whu.zhizhida.common.execution.ToolExecutionGuard;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;

class AgentTelemetryTest {
    static class Collector implements SpanExporter {
        final List<SpanData> spans = new CopyOnWriteArrayList<>();
        @Override public CompletableResultCode export(Collection<SpanData> batch) { spans.addAll(batch); return CompletableResultCode.ofSuccess(); }
        @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
        @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
    }

    static Tracer tracer(SdkTracerProvider provider) {
        return new OtelTracer(provider.get("agent-test"), new OtelCurrentTraceContext(), event -> { });
    }

    @Test void persistedParentSurvivesNewTracerThreadAndRecoveryWithoutInheritingCaller() throws Exception {
        var collector = new Collector();
        try (var sdk = SdkTracerProvider.builder().setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(collector)).build()) {
            var first = new AgentTelemetry(tracer(sdk));
            AgentTelemetry.Parent parent;
            try (var accepted = first.start(AgentTelemetry.Stage.ACCEPT)) { parent = accepted.parent(); }
            var restored = new AgentTelemetry(tracer(sdk));
            var task = new java.util.concurrent.FutureTask<Void>(() -> {
                try (var unrelated = restored.start(AgentTelemetry.Stage.ACCEPT)) {
                    try (var run = restored.resume(parent); var model = restored.start(AgentTelemetry.Stage.MODEL)) {
                        model.usage(new ModelUsage(ModelUsage.Status.REPORTED, 12L, 4L, 16L));
                    }
                    try (var resumed = restored.resume(parent)) { resumed.tag("agent.lease.epoch", "2"); }
                }
                return null;
            });
            Thread.ofVirtual().start(task);
            task.get(5, TimeUnit.SECONDS);
            var runs = collector.spans.stream().filter(s -> s.getName().equals("agent.run")).toList();
            assertThat(runs).hasSize(2).allSatisfy(s -> {
                assertThat(s.getTraceId()).isEqualTo(parent.traceId());
                assertThat(s.getParentSpanId()).isEqualTo(parent.spanId());
            });
            var model = collector.spans.stream().filter(s -> s.getName().equals("agent.model")).findFirst().orElseThrow();
            assertThat(model.getParentSpanId()).isEqualTo(runs.getFirst().getSpanId());
            assertThat(model.getAttributes().get(AttributeKey.stringKey("gen_ai.usage.input_tokens"))).isEqualTo("12");
        }
    }

    @Test void unsampledPersistedParentStaysUnsampledAndMalformedParentStartsFresh() {
        var collector = new Collector();
        try (var sdk = SdkTracerProvider.builder().setSampler(Sampler.parentBased(Sampler.alwaysOn()))
                .addSpanProcessor(SimpleSpanProcessor.create(collector)).build()) {
            var telemetry = new AgentTelemetry(tracer(sdk));
            var parent = new AgentTelemetry.Parent("1".repeat(32), "2".repeat(16), false);
            try (var run = telemetry.resume(parent); var model = telemetry.start(AgentTelemetry.Stage.MODEL)) {
                assertThat(run.parent().sampled()).isFalse();
            }
            assertThat(collector.spans).isEmpty();
            try (var old = telemetry.resume(new AgentTelemetry.Parent("secret", "bad", true))) {
                assertThat(old.parent().traceId()).isNotEqualTo(parent.traceId());
            }
            assertThat(collector.spans).hasSize(1);
        }
    }

    @Test void toolVirtualThreadReceivesTraceOnlyAndClearsScope() {
        var collector = new Collector();
        try (var sdk = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(collector)).build()) {
            var tracer = tracer(sdk);
            var telemetry = new AgentTelemetry(tracer);
            var identity = new InheritableThreadLocal<String>();
            identity.set("private-owner");
            try (var guard = new ToolExecutionGuard(telemetry); var run = telemetry.resume(null)) {
                String traceId = run.parent().traceId();
                assertThat(guard.call(2000, () -> {
                    assertThat(identity.get()).isNull();
                    return tracer.currentSpan().context().traceId();
                })).isEqualTo(traceId);
            } finally { identity.remove(); }
            assertThat(tracer.currentSpan()).isNull();
        }
    }

    @Test void unknownUsageAndErrorsNeverExportSourceTextOrPretendZeroTokens() {
        var collector = new Collector();
        try (var sdk = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(collector)).build()) {
            var telemetry = new AgentTelemetry(tracer(sdk));
            try (var model = telemetry.start(AgentTelemetry.Stage.MODEL)) {
                model.usage(ModelUsage.unknown(ModelUsage.Status.MISSING));
                model.failure("exception with private answer and token");
            }
            assertThatThrownBy(() -> telemetry.observe(AgentTelemetry.Stage.COMMIT, () -> {
                throw new IllegalStateException("private answer and token");
            })).isInstanceOf(IllegalStateException.class).hasMessage("private answer and token");
            assertThat(collector.spans.toString()).doesNotContain("private answer", "input_tokens", "output_tokens");
            assertThat(collector.spans.getFirst().getAttributes().get(AttributeKey.stringKey("agent.usage.status"))).isEqualTo("MISSING");
        }
    }

    @Test void telemetryFailuresDoNotChangeBusinessResultsOrExceptions() throws Exception {
        var broken = mock(Tracer.class);
        when(broken.spanBuilder()).thenThrow(new IllegalStateException("secret exporter failure"));
        when(broken.currentSpan()).thenThrow(new IllegalStateException("secret scope failure"));
        var telemetry = new AgentTelemetry(broken);
        assertThat(telemetry.observe(AgentTelemetry.Stage.COMMIT, () -> 42)).isEqualTo(42);
        assertThat(telemetry.wrap(() -> 43).call()).isEqualTo(43);
        assertThatThrownBy(() -> telemetry.observe(AgentTelemetry.Stage.MODEL, () -> {
            throw new IllegalArgumentException("original");
        })).isInstanceOf(IllegalArgumentException.class).hasMessage("original");
        try (var scope = telemetry.resume(null)) { assertThat(scope.parent()).isNull(); }
    }

    @Test void exporterQueueSaturationDoesNotBlockBusinessThread() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exporter = new Collector() {
            @Override public CompletableResultCode export(Collection<SpanData> batch) {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                return CompletableResultCode.ofFailure();
            }
        };
        try (var sdk = SdkTracerProvider.builder().addSpanProcessor(BatchSpanProcessor.builder(exporter)
                .setMaxQueueSize(2).setMaxExportBatchSize(1).setScheduleDelay(Duration.ofMillis(1)).build()).build()) {
            var telemetry = new AgentTelemetry(tracer(sdk));
            telemetry.observe(AgentTelemetry.Stage.RUN, () -> true);
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            try {
                org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                    for (int i = 0; i < 1000; i++) assertThat(telemetry.observe(AgentTelemetry.Stage.COMMIT, () -> true)).isTrue();
                });
            } finally { release.countDown(); }
        } finally { release.countDown(); }
    }

    @Test void realOtlpHttpExportUsesLocalEndpointAndFailureIsIsolated() throws Exception {
        var received = new java.util.concurrent.LinkedBlockingQueue<byte[]>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", exchange -> {
            received.add(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        var exporter = io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter.builder()
                .setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces")
                .setTimeout(Duration.ofMillis(500)).build();
        try (var sdk = SdkTracerProvider.builder().addSpanProcessor(BatchSpanProcessor.builder(exporter)
                .setScheduleDelay(Duration.ofMillis(10)).build()).build()) {
            var telemetry = new AgentTelemetry(tracer(sdk));
            assertThat(telemetry.observe(AgentTelemetry.Stage.COMMIT, () -> "committed")).isEqualTo("committed");
            var body = received.poll(5, TimeUnit.SECONDS);
            assertThat(body).isNotNull();
            assertThat(new String(body, java.nio.charset.StandardCharsets.ISO_8859_1)).contains("agent.commit");
            assertThat(telemetry.observe(AgentTelemetry.Stage.COMMIT, () -> "still-committed")).isEqualTo("still-committed");
        } finally { server.stop(0); }
    }
}
