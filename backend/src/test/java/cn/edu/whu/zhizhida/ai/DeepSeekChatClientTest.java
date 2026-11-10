package cn.edu.whu.zhizhida.ai;

import cn.edu.whu.zhizhida.common.UpstreamServiceException;
import cn.edu.whu.zhizhida.common.ErrorCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.ai.api.ModelUsage;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import cn.edu.whu.zhizhida.common.StructuredOutput;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class DeepSeekChatClientTest {

    @Mock
    private HttpClient httpClient;
    @Mock
    private HttpResponse<String> response;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new tools.jackson.databind.json.JsonMapper();
    }

    @Test
    void shouldParseJsonOutput() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {
                  "choices": [
                    {
                      "finish_reason": "stop",
                      "message": {"role": "assistant", "content": "{\\"question\\":\\"请介绍项目。\\"}"}
                    }
                  ]
                }
                """);
        when(httpClient.sendAsync(any(HttpRequest.class), anyStringBodyHandler())).thenReturn(CompletableFuture.completedFuture(response));

        JsonNode payload = client("test-key").completeJson(
                "只输出合法 json",
                "目标岗位：Java",
                0.5,
                600
        );

        assertThat(payload.path("question").asText()).isEqualTo("请介绍项目。");
    }

    @Test
    void shouldParseJsonWrappedInMarkdown() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {
                  "choices": [
                    {
                      "finish_reason": "stop",
                      "message": {
                        "role": "assistant",
                        "content": "```json\\n{\\"question\\":\\"请介绍项目。\\"}\\n```"
                      }
                    }
                  ]
                }
                """);
        when(httpClient.sendAsync(any(HttpRequest.class), anyStringBodyHandler())).thenReturn(CompletableFuture.completedFuture(response));

        JsonNode payload = client("test-key").completeJson("system", "user", 0.5, 600);

        assertThat(payload.path("question").asText()).isEqualTo("请介绍项目。");
    }

    @Test
    void shouldMapMalformedJsonToSharedParseError() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {
                  "choices": [
                    {
                      "finish_reason": "stop",
                      "message": {"role": "assistant", "content": "not-json"}
                    }
                  ]
                }
                """);
        when(httpClient.sendAsync(any(HttpRequest.class), anyStringBodyHandler())).thenReturn(CompletableFuture.completedFuture(response));

        assertThatThrownBy(() -> client("test-key").completeJson("system", "user", 0.5, 600))
                .isInstanceOf(UpstreamServiceException.class)
                .hasMessageContaining("响应解析失败");
    }

    @Test
    void shouldRejectMissingApiKeyBeforeSendingRequest() {
        assertThatThrownBy(() -> client("").completeJson("system", "user", 0.5, 600))
                .isInstanceOfSatisfying(UpstreamServiceException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.AI_CONFIGURATION_ERROR))
                .hasMessage("MODEL_CONFIGURATION");
        verifyNoInteractions(httpClient);
    }

    @Test
    void shouldClassifyTransportTimeout() throws Exception {
        when(httpClient.sendAsync(any(HttpRequest.class), anyStringBodyHandler()))
                .thenReturn(CompletableFuture.failedFuture(new HttpTimeoutException("timeout")));

        assertThatThrownBy(() -> client("test-key").completeJson("system", "user", 0.5, 600))
                .isInstanceOfSatisfying(UpstreamServiceException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.AI_TIMEOUT))
                .hasMessage("MODEL_NETWORK");
    }

    @Test
    void shouldDistinguishNetworkFailureFromTimeout() throws Exception {
        when(httpClient.sendAsync(any(HttpRequest.class), anyStringBodyHandler()))
                .thenReturn(CompletableFuture.failedFuture(new java.net.ConnectException("private host")));

        assertThatThrownBy(() -> client("test-key").completeJson("system", "user", 0.5, 600))
                .isInstanceOfSatisfying(UpstreamServiceException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.AI_NETWORK_ERROR))
                .hasMessage("MODEL_NETWORK");
    }

    @Test
    void shouldMapRateLimitWithoutReturningProviderBody() throws Exception {
        when(response.statusCode()).thenReturn(429);
        when(httpClient.sendAsync(any(HttpRequest.class), anyStringBodyHandler())).thenReturn(CompletableFuture.completedFuture(response));

        assertThatThrownBy(() -> client("test-key").completeJson("system", "user", 0.5, 600))
                .isInstanceOfSatisfying(UpstreamServiceException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.AI_RATE_LIMITED))
                .hasMessage("MODEL_HTTP_429")
                .hasMessageNotContaining("provider internal details");
    }

    @Test
    void shouldReject401Unauthorized() throws Exception {
        when(response.statusCode()).thenReturn(401);
        when(httpClient.sendAsync(any(HttpRequest.class), anyStringBodyHandler())).thenReturn(CompletableFuture.completedFuture(response));

        assertThatThrownBy(() -> client("test-key").completeJson("system", "user", 0.5, 600))
                .isInstanceOfSatisfying(UpstreamServiceException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.AI_AUTH_FAILED))
                .hasMessage("MODEL_HTTP_401");
    }

    @Test
    void shouldReject403Forbidden() throws Exception {
        when(response.statusCode()).thenReturn(403);
        when(httpClient.sendAsync(any(HttpRequest.class), anyStringBodyHandler())).thenReturn(CompletableFuture.completedFuture(response));

        assertThatThrownBy(() -> client("test-key").completeJson("system", "user", 0.5, 600))
                .isInstanceOfSatisfying(UpstreamServiceException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.AI_AUTH_FAILED))
                .hasMessage("MODEL_HTTP_403");
    }

    @Test
    void shouldRejectTruncatedFinishReason() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {
                  "choices": [
                    {
                      "finish_reason": "length",
                      "message": {"role": "assistant", "content": "{\\"key\\":\\"value\\"}"}
                    }
                  ]
                }
                """);
        when(httpClient.sendAsync(any(HttpRequest.class), anyStringBodyHandler())).thenReturn(CompletableFuture.completedFuture(response));

        assertThatThrownBy(() -> client("test-key").completeJson("system", "user", 0.5, 600))
                .isInstanceOfSatisfying(UpstreamServiceException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.AI_INVALID_RESPONSE))
                .hasMessageContaining("被截断");
    }

    @Test
    void shouldRejectBlankContent() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {
                  "choices": [
                    {
                      "finish_reason": "stop",
                      "message": {"role": "assistant", "content": ""}
                    }
                  ]
                }
                """);
        when(httpClient.sendAsync(any(HttpRequest.class), anyStringBodyHandler())).thenReturn(CompletableFuture.completedFuture(response));

        assertThatThrownBy(() -> client("test-key").completeJson("system", "user", 0.5, 600))
                .isInstanceOf(UpstreamServiceException.class)
                .hasMessageContaining("没有返回可用内容");
    }

    @Test
    void shouldRejectBlankApiKey() {
        assertThatThrownBy(() -> client("   ").completeJson("system", "user", 0.5, 600))
                .isInstanceOf(UpstreamServiceException.class)
                .hasMessage("MODEL_CONFIGURATION");
    }

    @Test
    void shouldHandleNullApiKey() {
        assertThatThrownBy(() -> client(null).completeJson("system", "user", 0.5, 600))
                .isInstanceOf(UpstreamServiceException.class)
                .hasMessage("MODEL_CONFIGURATION");
    }

    @Test
    void shouldHandleNonStandardHttpError() throws Exception {
        when(response.statusCode()).thenReturn(500);
        when(httpClient.sendAsync(any(HttpRequest.class), anyStringBodyHandler())).thenReturn(CompletableFuture.completedFuture(response));

        assertThatThrownBy(() -> client("test-key").completeJson("system", "user", 0.5, 600))
                .isInstanceOfSatisfying(UpstreamServiceException.class,
                        ex -> assertThat(ex.code()).isEqualTo("MODEL_HTTP_500"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "[{\"question\":\"unsafe-root\"}]", "{} {}"})
    void shouldRejectNonObjectOrTrailingJson(String content) throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(objectMapper.writeValueAsString(java.util.Map.of(
                "choices", java.util.List.of(java.util.Map.of("finish_reason", "stop", "message", java.util.Map.of("role", "assistant", "content", content))))));
        when(httpClient.sendAsync(any(HttpRequest.class), anyStringBodyHandler())).thenReturn(CompletableFuture.completedFuture(response));

        assertThatThrownBy(() -> client("test-key").completeJson("system", "user", 0.5, 600))
                .isInstanceOf(UpstreamServiceException.class);
    }

    @Test
    void sharedGuardStopsSendingAfterRepeatedTimeouts() throws Exception {
        when(httpClient.sendAsync(any(HttpRequest.class), anyStringBodyHandler()))
                .thenReturn(CompletableFuture.failedFuture(new HttpTimeoutException("timeout")));
        // Redis 依次允许三次调用，再报告共享熔断；状态转换由真实 Redis 测试覆盖。
        var admissions = new java.util.ArrayDeque<>(java.util.List.of(1L, 1L, 1L, -1L));
        var settlements = new java.util.ArrayList<String>();
        var redis = org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class, invocation -> {
            if (invocation.getMethod().getName().equals("execute")) {
                var script = (org.springframework.data.redis.core.script.RedisScript<?>) invocation.getArgument(0);
                if (script.getResultType() == java.util.List.class) return java.util.List.of(admissions.removeFirst(), 0L);
                settlements.add(invocation.getArgument(4));
                return 1L;
            }
            return org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation);
        });
        var guard = new AiCallGuard(redis, "test", 4, 3, 30, BusinessMetrics.disabled());
        DeepSeekChatClient client = new DeepSeekChatClient("test-key", "https://api.deepseek.com", "deepseek-chat",
                "", 30, objectMapper, httpClient, guard);
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.completeJson("system", "user", 0.5, 600))
                    .isInstanceOf(UpstreamServiceException.class);
        }
        assertThatThrownBy(() -> client.completeJson("system", "user", 0.5, 600))
                .isInstanceOf(BusinessException.class);
        verify(httpClient, times(3)).sendAsync(any(HttpRequest.class), anyStringBodyHandler());
        assertThat(settlements).containsExactly("0", "0", "0");
        assertThat(admissions).isEmpty();
    }

    @Test
    void interruptionPreservesThreadFlag() throws Exception {
        when(httpClient.sendAsync(any(HttpRequest.class), anyStringBodyHandler())).thenReturn(new CompletableFuture<>());
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> client("test-key").completeJson("system", "user", 0.5, 600))
                    .isInstanceOf(UpstreamServiceException.class).hasMessage("MODEL_INTERRUPTED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void rejectsPromptAndOutputBudgetsBeforeNetworkCall() throws Exception {
        DeepSeekChatClient client = client("test-key");
        assertThatThrownBy(() -> client.completeJson("system", "x".repeat(32001), 0.5, 600))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.completeJson("system", "user", 0.5, 8193))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(httpClient);
    }

    @Test
    void httpAndProtocolSuccessDoNotImplyBusinessSuccessOrEraseObservedUsage() throws Exception {
        var registry = new SimpleMeterRegistry();
        var metrics = new BusinessMetrics(registry);
        stubEnvelope("{}", "stop", "{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}");
        var client = measuredClient(metrics);
        assertThatThrownBy(() -> metrics.measure(BusinessMetrics.Operation.RESUME_OPTIMIZE,
                () -> StructuredOutput.array(client.completeJson("system", "user", 0.2, 8192), "changes", 100)))
                .isInstanceOf(UpstreamServiceException.class).hasMessageContaining("changes");
        assertThat(registry.get("zhizhida.business").tags("operation", "ai_http", "outcome", "success").timer().count()).isEqualTo(1);
        assertThat(registry.get("zhizhida.business").tags("operation", "ai", "outcome", "success").timer().count()).isEqualTo(1);
        assertThat(registry.get("zhizhida.business").tags("operation", "resume_optimize", "outcome", "failure").timer().count()).isEqualTo(1);
        assertThat(registry.get("zhizhida.ai.tokens").tag("kind", "total").counter().count()).isEqualTo(15);
    }

    @ParameterizedTest
    @ValueSource(strings = {"length", "content_filter", "tool_calls", "", "unknown"})
    void abnormalCompletionNeverReturnsSuccessButPreservesUsage(String finish) throws Exception {
        var registry = new SimpleMeterRegistry();
        var metrics = new BusinessMetrics(registry);
        stubEnvelope("{}", finish, "{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}");
        assertThatThrownBy(() -> measuredClient(metrics).complete("system", "user", 0.1, 100))
                .isInstanceOf(UpstreamServiceException.class);
        assertThat(registry.get("zhizhida.business").tags("operation", "ai", "outcome", "failure").timer().count()).isEqualTo(1);
        assertThat(registry.get("zhizhida.ai.tokens").tag("kind", "total").counter().count()).isEqualTo(15);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "{\"prompt_tokens\":-1,\"completion_tokens\":2,\"total_tokens\":1}",
            "{\"prompt_tokens\":\"10\",\"completion_tokens\":5,\"total_tokens\":15}",
            "{\"prompt_tokens\":1.5,\"completion_tokens\":5,\"total_tokens\":6}",
            "{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":999}",
            "{\"prompt_tokens\":9223372036854775808,\"completion_tokens\":5,\"total_tokens\":5}"})
    void missingOrInvalidUsageIsUnknownInsteadOfZero(String usage) throws Exception {
        var registry = new SimpleMeterRegistry();
        var metrics = new BusinessMetrics(registry);
        stubEnvelope("{}", "stop", usage);
        var result = measuredClient(metrics).complete("system", "user", 0.1, 100);
        var status = "null".equals(usage) ? ModelUsage.Status.MISSING : ModelUsage.Status.INVALID;
        assertThat(result.usage().status()).isEqualTo(status);
        assertThat(result.usage().totalTokens()).isNull();
        assertThat(registry.find("zhizhida.ai.tokens").counters()).isEmpty();
        assertThat(registry.get("zhizhida.ai.usage").tag("status", status.name().toLowerCase(java.util.Locale.ROOT))
                .counter().count()).isEqualTo(1);
    }

    @Test
    void returnsReportedUsageAndCountsItOnceWhenPayloadParsingFails() throws Exception {
        var registry = new SimpleMeterRegistry();
        var metrics = new BusinessMetrics(registry);
        stubEnvelope("{}", "stop", "{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}");
        var client = measuredClient(metrics);
        var result = client.complete("system", "user", 0.1, 100);
        assertThat(result.usage()).isEqualTo(new ModelUsage(ModelUsage.Status.REPORTED, 10L, 5L, 15L));
        stubEnvelope("not JSON", "stop", "{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}");
        assertThatThrownBy(() -> client.completeJson("system", "user", 0.1, 100)).isInstanceOf(UpstreamServiceException.class);
        assertThat(registry.get("zhizhida.ai.tokens").tag("kind", "total").counter().count()).isEqualTo(30);
    }

    private DeepSeekChatClient measuredClient(BusinessMetrics metrics) {
        return new DeepSeekChatClient("test-key", "https://example.invalid", "test", "", 30, objectMapper,
                httpClient, AiGuardFixtures.guard(metrics), metrics);
    }

    private void stubEnvelope(String content, String finish, String usage) throws Exception {
        var root = objectMapper.createObjectNode();
        root.set("usage", objectMapper.readTree(usage));
        root.putArray("choices").addObject().put("finish_reason", finish).putObject("message").put("role", "assistant").put("content", content);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(root.toString());
        when(httpClient.sendAsync(any(HttpRequest.class), anyStringBodyHandler())).thenReturn(CompletableFuture.completedFuture(response));
    }

    @SuppressWarnings("unchecked")
    private HttpResponse.BodyHandler<String> anyStringBodyHandler() {
        return any(HttpResponse.BodyHandler.class);
    }

    private DeepSeekChatClient client(String apiKey) {
        return new DeepSeekChatClient(
                apiKey,
                "https://api.deepseek.com",
                "deepseek-chat",
                "",
                30,
                objectMapper,
                httpClient,
                AiGuardFixtures.guard()
        );
    }
}
