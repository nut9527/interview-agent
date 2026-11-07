package cn.edu.whu.zhizhida.ai;

import cn.edu.whu.zhizhida.ai.api.ToolCallingChatClient;
import cn.edu.whu.zhizhida.common.UpstreamServiceException;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeepSeekToolChatMetricsTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private HttpServer server;
    private DeepSeekToolChatClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        var metrics = new BusinessMetrics(registry);
        client = createClient("http://127.0.0.1:" + server.getAddress().getPort(), metrics);
    }

    DeepSeekToolChatClient createClient(String url, BusinessMetrics metrics) {
        return new DeepSeekToolChatClient("synthetic-key", url, "fixture", "disabled", new JsonMapper(),
                AiGuardFixtures.guard(metrics), metrics);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        registry.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"中文回答\"}",
            "\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"tool_calls\":["
                    + "{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"read_answer_evidence\",\"arguments\":\"{}\"}}]}"
    })
    void finalAndToolRepliesRecordOneHttpCallAndReportedUsage(String choice) {
        respond(200, "{\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":5},\"choices\":[{"
                + choice + "}]}");

        var response = client.complete(request());

        assertThat(response.message().toString()).containsAnyOf("中文回答", "read_answer_evidence");
        assertTimer("ai_http", "success");
        assertTimer("ai", "success");
        assertThat(registry.get("zhizhida.ai.tokens").tag("kind", "total").counter().count()).isEqualTo(5);
        assertThat(registry.find("zhizhida.business").tag("outcome", "failure").timer()).isNull();
    }

    @Test
    void invalidModelOutputIsAnAiFailureAfterASuccessfulHttpCall() {
        respond(200, "{\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":5},\"choices\":[]}");

        assertThatThrownBy(() -> client.complete(request()))
                .isInstanceOf(UpstreamServiceException.class).hasMessage("MODEL_PROTOCOL");

        assertTimer("ai_http", "success");
        assertTimer("ai", "failure");
        assertThat(registry.get("zhizhida.ai.tokens").tag("kind", "total").counter().count()).isEqualTo(5);
        assertThat(registry.find("zhizhida.business").tags("operation", "ai_http", "outcome", "failure").timer()).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 429, 503})
    void unsuccessfulHttpCallsAreCountedWithoutParsingProviderErrors(int status) {
        respond(status, "private provider detail");

        assertThatThrownBy(() -> client.complete(request()))
                .isInstanceOf(UpstreamServiceException.class).hasMessage("MODEL_HTTP_" + status);

        assertTimer("ai_http", "failure");
        assertTimer("ai", "failure");
        assertThat(registry.find("zhizhida.ai.usage").counter()).isNull();
    }

    @Test
    void interruptedCallsRecordFailureAndPreserveTheInterrupt() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> client.complete(request()))
                    .isInstanceOf(UpstreamServiceException.class).hasMessage("MODEL_INTERRUPTED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertTimer("ai_http", "failure");
        } finally {
            Thread.interrupted();
        }
    }

    private ToolCallingChatClient.Request request() {
        return new ToolCallingChatClient.Request(client.configuration(),
                List.of(new JsonMapper().createObjectNode().put("role", "user").put("content", "fixture")), List.of(), true,
                Duration.ofSeconds(2), "metrics-test");
    }

    private void assertTimer(String operation, String outcome) {
        assertThat(registry.get("zhizhida.business").tags("operation", operation, "outcome", outcome)
                .timer().count()).isEqualTo(1);
    }

    private void respond(int status, String body) {
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }
}
