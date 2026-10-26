package cn.edu.whu.zhizhida.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;

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
import cn.edu.whu.zhizhida.resume.DefaultAiResumeService;
import cn.edu.whu.zhizhida.resume.api.AiOptimizeRequest;
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
        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldParseJsonOutput() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {
                  "choices": [
                    {
                      "finish_reason": "stop",
                      "message": {"content": "{\\"question\\":\\"请介绍项目。\\"}"}
                    }
                  ]
                }
                """);
        when(httpClient.send(any(HttpRequest.class), anyStringBodyHandler())).thenReturn(response);

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
                        "content": "```json\\n{\\"question\\":\\"请介绍项目。\\"}\\n```"
                      }
                    }
                  ]
                }
                """);
        when(httpClient.send(any(HttpRequest.class), anyStringBodyHandler())).thenReturn(response);

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
                      "message": {"content": "not-json"}
                    }
                  ]
                }
                """);
        when(httpClient.send(any(HttpRequest.class), anyStringBodyHandler())).thenReturn(response);

        assertThatThrownBy(() -> client("test-key").completeJson("system", "user", 0.5, 600))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("响应解析失败");
    }

    @Test
    void shouldRejectMissingApiKeyBeforeSendingRequest() {
        assertThatThrownBy(() -> client("").completeJson("system", "user", 0.5, 600))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEEPSEEK_API_KEY");
    }

    @Test
    void shouldMapTimeoutToRetryableMessage() throws Exception {
        when(httpClient.send(any(HttpRequest.class), anyStringBodyHandler()))
                .thenThrow(new HttpTimeoutException("timeout"));

        assertThatThrownBy(() -> client("test-key").completeJson("system", "user", 0.5, 600))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("超时");
    }

    @Test
    void shouldMapRateLimitWithoutReturningProviderBody() throws Exception {
        when(response.statusCode()).thenReturn(429);
        when(httpClient.send(any(HttpRequest.class), anyStringBodyHandler())).thenReturn(response);

        assertThatThrownBy(() -> client("test-key").completeJson("system", "user", 0.5, 600))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("余额或稍后重试")
                .hasMessageNotContaining("provider internal details");
    }

    @Test
    void shouldReject401Unauthorized() throws Exception {
        when(response.statusCode()).thenReturn(401);
        when(httpClient.send(any(HttpRequest.class), anyStringBodyHandler())).thenReturn(response);

        assertThatThrownBy(() -> client("test-key").completeJson("system", "user", 0.5, 600))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("鉴权失败");
    }

    @Test
    void shouldReject403Forbidden() throws Exception {
        when(response.statusCode()).thenReturn(403);
        when(httpClient.send(any(HttpRequest.class), anyStringBodyHandler())).thenReturn(response);

        assertThatThrownBy(() -> client("test-key").completeJson("system", "user", 0.5, 600))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("鉴权失败");
    }

    @Test
    void shouldRejectTruncatedFinishReason() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {
                  "choices": [
                    {
                      "finish_reason": "length",
                      "message": {"content": "{\\"key\\":\\"value\\"}"}
                    }
                  ]
                }
                """);
        when(httpClient.send(any(HttpRequest.class), anyStringBodyHandler())).thenReturn(response);

        assertThatThrownBy(() -> client("test-key").completeJson("system", "user", 0.5, 600))
                .isInstanceOf(IllegalStateException.class)
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
                      "message": {"content": ""}
                    }
                  ]
                }
                """);
        when(httpClient.send(any(HttpRequest.class), anyStringBodyHandler())).thenReturn(response);

        assertThatThrownBy(() -> client("test-key").completeJson("system", "user", 0.5, 600))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有返回可用内容");
    }

    @Test
    void shouldRejectBlankApiKey() {
        assertThatThrownBy(() -> client("   ").completeJson("system", "user", 0.5, 600))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEEPSEEK_API_KEY");
    }

    @Test
    void shouldHandleNullApiKey() {
        assertThatThrownBy(() -> client(null).completeJson("system", "user", 0.5, 600))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEEPSEEK_API_KEY");
    }

    @Test
    void shouldHandleNonStandardHttpError() throws Exception {
        when(response.statusCode()).thenReturn(500);
        when(httpClient.send(any(HttpRequest.class), anyStringBodyHandler())).thenReturn(response);

        assertThatThrownBy(() -> client("test-key").completeJson("system", "user", 0.5, 600))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 500");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "[{\"question\":\"unsafe-root\"}]", "{} {}"})
    void shouldRejectNonObjectOrTrailingJson(String content) throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(objectMapper.writeValueAsString(java.util.Map.of(
                "choices", java.util.List.of(java.util.Map.of("finish_reason", "stop", "message", java.util.Map.of("content", content))))));
        when(httpClient.send(any(HttpRequest.class), anyStringBodyHandler())).thenReturn(response);

        assertThatThrownBy(() -> client("test-key").completeJson("system", "user", 0.5, 600))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sharedGuardStopsSendingAfterRepeatedTimeouts() throws Exception {
        when(httpClient.send(any(HttpRequest.class), anyStringBodyHandler()))
                .thenThrow(new HttpTimeoutException("timeout"));
        DeepSeekChatClient client = client("test-key");
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.completeJson("system", "user", 0.5, 600))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> client.completeJson("system", "user", 0.5, 600))
                .isInstanceOf(BusinessException.class);
        verify(httpClient, times(3)).send(any(HttpRequest.class), anyStringBodyHandler());
    }

    @Test
    void interruptionPreservesThreadFlag() throws Exception {
        when(httpClient.send(any(HttpRequest.class), anyStringBodyHandler())).thenThrow(new InterruptedException());
        try {
            assertThatThrownBy(() -> client("test-key").completeJson("system", "user", 0.5, 600))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("中断");
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
        assertThatThrownBy(() -> client.completeJson("system", "user", 0.5, 2001))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(httpClient);
    }

    @Test
    void httpAndProtocolSuccessDoNotImplyBusinessSuccessOrEraseObservedUsage() throws Exception {
        var registry = new SimpleMeterRegistry();
        var metrics = new BusinessMetrics(registry);
        stubEnvelope("{}", "stop", "{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}");
        var client = measuredClient(metrics);
        var service = new DefaultAiResumeService(client, metrics);
        assertThatThrownBy(() -> service.optimize(new AiOptimizeRequest("经历", "岗位")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("optimizedText");
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
                .isInstanceOf(IllegalStateException.class);
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
        assertThatThrownBy(() -> client.completeJson("system", "user", 0.1, 100)).isInstanceOf(IllegalStateException.class);
        assertThat(registry.get("zhizhida.ai.tokens").tag("kind", "total").counter().count()).isEqualTo(30);
    }

    private DeepSeekChatClient measuredClient(BusinessMetrics metrics) {
        return new DeepSeekChatClient("test-key", "https://example.invalid", "test", "", 30, objectMapper,
                httpClient, new AiCallGuard(4, 3, 30, metrics), metrics);
    }

    private void stubEnvelope(String content, String finish, String usage) throws Exception {
        var root = objectMapper.createObjectNode();
        root.set("usage", objectMapper.readTree(usage));
        root.putArray("choices").addObject().put("finish_reason", finish).putObject("message").put("content", content);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(root.toString());
        when(httpClient.send(any(HttpRequest.class), anyStringBodyHandler())).thenReturn(response);
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
                httpClient
        );
    }
}
