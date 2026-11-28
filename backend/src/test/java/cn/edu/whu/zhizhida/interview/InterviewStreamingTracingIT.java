package cn.edu.whu.zhizhida.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import cn.edu.whu.zhizhida.ai.api.ModelUsage;
import cn.edu.whu.zhizhida.ai.api.ToolCallingChatClient;
import cn.edu.whu.zhizhida.common.UpstreamServiceException;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunStore;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

class InterviewStreamingTracingIT {

        @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                        "interview.runs.worker-enabled=false",
                        "ai.provider=deepseek",
                        "interview.runs.loop-policy=A1",
                        "management.tracing.sampling.probability=1",
                        "management.tracing.baggage.enabled=false",
                        "management.tracing.export.otlp.enabled=false"
        })
        @Import(Tracing.TraceConfig.class)
        @Nested
        class Tracing extends InterviewRunScenario {
                static class Collector implements SpanExporter {
                        final List<SpanData> spans = new CopyOnWriteArrayList<>();

                        @Override
                        public CompletableResultCode export(Collection<SpanData> spans) {
                                this.spans.addAll(spans);
                                return CompletableResultCode.ofSuccess();
                        }

                        @Override
                        public CompletableResultCode flush() {
                                return CompletableResultCode.ofSuccess();
                        }

                        @Override
                        public CompletableResultCode shutdown() {
                                return CompletableResultCode.ofSuccess();
                        }
                }

                @TestConfiguration
                static class TraceConfig {
                        @Bean
                        Collector traceCollector() {
                                return new Collector();
                        }
                }

                @MockitoBean
                ToolCallingChatClient client;
                @Autowired
                JdbcTemplate jdbc;
                @Autowired
                Collector collector;
                @Autowired
                SdkTracerProvider sdk;

                @org.springframework.beans.factory.annotation.Value("${local.server.port}")
                int port;

                @BeforeEach
                void setup() throws Exception {
                        when(client.configuration())
                                        .thenReturn(
                                                        new ToolCallingChatClient.Config(
                                                                        "trace-fixture", "enabled", "fixture-v1"));
                        sdk.forceFlush().join(3, java.util.concurrent.TimeUnit.SECONDS);
                        collector.spans.clear();
                }

                String accept(String key, String traceId) throws Exception {
                        try (var http = HttpClient.newHttpClient()) {
                                var request = HttpRequest.newBuilder(
                                                URI.create(
                                                                "http://127.0.0.1:"
                                                                                + port
                                                                                + "/api/interviews/start-commands"))
                                                .timeout(Duration.ofSeconds(10))
                                                .header("Authorization", "Bearer " + token)
                                                .header("Content-Type", "application/json")
                                                .header("traceparent", "00-" + traceId + "-1111111111111111-01")
                                                .header("baggage", "secret=must-not-be-exported")
                                                .POST(
                                                                HttpRequest.BodyPublishers.ofString(
                                                                                objectMapper.writeValueAsString(
                                                                                                Map.of(
                                                                                                                "requestId",
                                                                                                                key,
                                                                                                                "configuration",
                                                                                                                Map.of(
                                                                                                                                "targetRole",
                                                                                                                                "Java 后端",
                                                                                                                                "resumeSummary",
                                                                                                                                "sensitive-resume-canary",
                                                                                                                                "totalRounds",
                                                                                                                                3)))))
                                                .build();
                                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                                assertThat(response.statusCode()).isEqualTo(202);
                                return objectMapper.readTree(response.body()).path("data").path("runId").asString();
                        }
                }

                ToolCallingChatClient.Response finalOutput() {
                        return new ToolCallingChatClient.Response(
                                        objectMapper
                                                        .createObjectNode()
                                                        .put("role", "assistant")
                                                        .put(
                                                                        "content",
                                                                        "{\"version\":\"phased-v2\",\"phase\":\"FINAL\",\"result\":{\"question\":\"请说明你在相关经历中承担的个人职责？\",\"questionCompetencyId\":\"experience\"}}"),
                                        "stop",
                                        ModelUsage.unknown(ModelUsage.Status.MISSING));
                }

                ToolCallingChatClient.Response actionOutput() {
                        return new ToolCallingChatClient.Response(
                                        objectMapper
                                                        .createObjectNode()
                                                        .put("role", "assistant")
                                                        .put(
                                                                        "content",
                                                                        "{\"version\":\"phased-v2\",\"phase\":\"ACTION\",\"action\":\"COMPLETE\",\"reason\":\"已核对开场材料\"}"),
                                        "stop",
                                        ModelUsage.unknown(ModelUsage.Status.MISSING));
                }

                List<SpanData> spans(String id) {
                        sdk.forceFlush().join(3, java.util.concurrent.TimeUnit.SECONDS);
                        return collector.spans.stream()
                                        .filter(
                                                        s -> id.equals(
                                                                        s.getAttributes()
                                                                                        .get(AttributeKey.stringKey(
                                                                                                        "agent.run.id"))))
                                        .toList();
                }

                static String tag(SpanData span, String key) {
                        return span.getAttributes().get(AttributeKey.stringKey(key));
                }

                @Test
                void realHttpWorkerToolsAndLedgerShareOneTraceAndReplayKeepsOriginalParent()
                                throws Exception {
                        var message = objectMapper
                                        .createObjectNode()
                                        .put("role", "assistant")
                                        .putNull("content")
                                        .put("reasoning_content", "private-reasoning-canary");
                        message.putArray("tool_calls")
                                        .addObject()
                                        .put("id", "secret-tool-call-canary")
                                        .put("type", "function")
                                        .putObject("function")
                                        .put("name", "read_material_section")
                                        .put("arguments", "{\"sectionId\":\"resume-0\",\"maxChars\":2000}");
                        when(client.complete(any()))
                                        .thenReturn(
                                                        new ToolCallingChatClient.Response(
                                                                        message,
                                                                        "tool_calls",
                                                                        new ModelUsage(ModelUsage.Status.REPORTED, 10L,
                                                                                        5L, 15L)),
                                                        new ToolCallingChatClient.Response(
                                                                        objectMapper.readTree(
                                                                                        message.toString()
                                                                                                        .replace(
                                                                                                                        "secret-tool-call-canary",
                                                                                                                        "second-call-canary")),
                                                                        "tool_calls",
                                                                        ModelUsage.unknown(ModelUsage.Status.MISSING)),
                                                        actionOutput(),
                                                        finalOutput());
                        String key = UUID.randomUUID().toString(), traceId = "a".repeat(32);
                        String id = accept(key, traceId);
                        var parent = store.traceParent(new InterviewRunStore.Claim(id, owner, null, 0));
                        assertThat(parent.traceId()).isEqualTo(traceId);
                        assertThat(accept(key, "b".repeat(32))).isEqualTo(id);
                        assertThat(store.traceParent(new InterviewRunStore.Claim(id, owner, null, 0)))
                                        .isEqualTo(parent);
                        // 与数据库调度一样使用独立虚拟线程，验证执行不依赖请求线程的 ThreadLocal。
                        var execution = new java.util.concurrent.FutureTask<Void>(
                                        () -> {
                                                run(id);
                                                return null;
                                        });
                        Thread.ofVirtual().start(execution);
                        execution.get(15, java.util.concurrent.TimeUnit.SECONDS);
                        assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
                        var recorded = spans(id);
                        assertThat(recorded).allSatisfy(s -> assertThat(s.getTraceId()).isEqualTo(traceId));
                        var attempts = recorded.stream().filter(s -> s.getName().equals("agent.model")).toList();
                        assertThat(attempts).hasSize(4);
                        assertThat(attempts.stream().map(s -> tag(s, "agent.attempt.id")))
                                        .containsExactlyInAnyOrderElementsOf(
                                                        jdbc.queryForList(
                                                                        "SELECT attempt_id FROM interview_model_attempts WHERE run_id=?",
                                                                        String.class,
                                                                        id));
                        var root = recorded.stream()
                                        .filter(s -> s.getName().equals("agent.run"))
                                        .findFirst()
                                        .orElseThrow();
                        assertThat(root.getParentSpanId()).isEqualTo(parent.spanId());
                        assertThat(attempts)
                                        .allSatisfy(s -> assertThat(s.getParentSpanId()).isEqualTo(root.getSpanId()));
                        assertThat(
                                        recorded.stream()
                                                        .filter(s -> s.getName().equals("agent.tool"))
                                                        .map(s -> tag(s, "agent.tool.mode")))
                                        .containsExactlyInAnyOrder("EXECUTED", "CACHE");
                        var trace = collector.spans.stream().filter(s -> s.getTraceId().equals(traceId)).toList();
                        assertThat(trace.stream().map(SpanData::getName))
                                        .contains("agent.validate", "agent.commit");
                        assertThat(trace.toString())
                                        .doesNotContain(
                                                        "sensitive-resume-canary",
                                                        "private-reasoning-canary",
                                                        "secret-tool-call-canary",
                                                        "second-call-canary",
                                                        "must-not-be-exported",
                                                        token);
                }

                @Test
                void durableRetryCreatesSiblingExecutionAndOneSpanPerNewAttempt() throws Exception {
                        when(client.complete(any()))
                                        .thenThrow(UpstreamServiceException.coded("MODEL_HTTP_503"))
                                        .thenReturn(actionOutput(), finalOutput());
                        String id = accept(UUID.randomUUID().toString(), "c".repeat(32));
                        run(id);
                        assertThat(store.get(owner, id).status()).isEqualTo("ACCEPTED");
                        jdbc.update(
                                        "UPDATE interview_runs SET not_before=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
                                        id);
                        run(id);
                        assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
                        var recorded = spans(id);
                        var runs = recorded.stream().filter(s -> s.getName().equals("agent.run")).toList();
                        assertThat(runs).hasSize(2);
                        assertThat(runs.get(0).getParentSpanId()).isEqualTo(runs.get(1).getParentSpanId());
                        assertThat(runs.get(0).getSpanId()).isNotEqualTo(runs.get(1).getSpanId());
                        var models = recorded.stream().filter(s -> s.getName().equals("agent.model")).toList();
                        assertThat(models).hasSize(3);
                        assertThat(tag(models.getFirst(), "error.type")).isEqualTo("MODEL_HTTP_503");
                        assertThat(tag(models.getFirst(), "gen_ai.usage.input_tokens")).isNull();
                }

                @Test
                void oldRowsWithoutTraceContextStillExecute() throws Exception {
                        when(client.complete(any())).thenReturn(actionOutput(), finalOutput());
                        String id = accept(UUID.randomUUID().toString(), "d".repeat(32));
                        jdbc.update(
                                        "UPDATE interview_runs SET trace_id=NULL,trace_parent_span_id=NULL,trace_sampled=NULL WHERE id=?",
                                        id);
                        run(id);
                        assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
                        var root = spans(id).stream()
                                        .filter(s -> s.getName().equals("agent.run"))
                                        .findFirst()
                                        .orElseThrow();
                        assertThat(root.getTraceId()).isNotEqualTo("d".repeat(32));
                        assertThat(root.getParentSpanId()).isEqualTo("0".repeat(16));
                }

                @Test
                void restoredResponseDoesNotFabricateAnotherModelSpanOrAttempt() throws Exception {
                        String id = accept(UUID.randomUUID().toString(), "e".repeat(32));
                        var claim = store.claim(store.get(owner, id));
                        var checkpoint = objectMapper
                                        .createObjectNode()
                                        .put("stage", "MODEL")
                                        .put("outputPhase", "FINAL")
                                        .put("actionRepaired", false)
                                        .put("repaired", false);
                        checkpoint.putArray("sources");
                        checkpoint
                                        .putArray("messages")
                                        .addObject()
                                        .put("role", "user")
                                        .put("content", "fixture");
                        store.checkpoint(claim, checkpoint.toString(), "MODEL", null, null);
                        String attempt = store.reserveModelAttempt(claim, prepared("fixture")).id();
                        checkpoint.put("stage", "VALIDATE");
                        checkpoint.withArray("messages").add(finalOutput().message());
                        checkpoint.set("response", finalOutput().message());
                        store.response(claim, attempt, "{}", "{}", 1, checkpoint.toString(), "VALIDATE");
                        jdbc.update(
                                        "UPDATE interview_runs SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
                                        id);
                        run(id);
                        assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
                        assertThat(store.get(owner, id).attempts()).isEqualTo(1);
                        verify(client, never()).complete(any());
                        assertThat(spans(id).stream().map(SpanData::getName))
                                        .contains("agent.run")
                                        .doesNotContain("agent.model");
                }
        }
}
