package cn.edu.whu.zhizhida.ai;

import static org.assertj.core.api.Assertions.*;

import cn.edu.whu.zhizhida.ai.api.ModelUsage;
import cn.edu.whu.zhizhida.common.UpstreamServiceException;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class LangChain4jJsonTest {
    private final JsonMapper json = new JsonMapper();

    @Test
    void preservesJsonContractAndRawUsage() throws Exception {
        var count = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/chat/completions",
                exchange -> {
                    count.incrementAndGet();
                    var body = json.readTree(exchange.getRequestBody().readAllBytes());
                    assertThat(body.path("temperature").asDouble()).isEqualTo(0.35);
                    assertThat(body.path("max_tokens").asInt()).isEqualTo(300);
                    assertThat(body.at("/response_format/type").asString())
                            .isEqualTo("json_object");
                    assertThat(body.has("tools")).isFalse();
                    var response =
                            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"ok\\\":true}\"}}],\"usage\":{\"prompt_tokens\":\"2\",\"completion_tokens\":3,\"total_tokens\":5}}";
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            var result = client(server).complete("Return JSON", "synthetic", 0.35, 300);
            assertThat(result.payload().path("ok").asBoolean()).isTrue();
            assertThat(result.usage().status()).isEqualTo(ModelUsage.Status.INVALID);
            assertThat(count).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 307, 308})
    void sdkNeverRetriesOrRedirects(int status) throws Exception {
        var count = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    count.incrementAndGet();
                    exchange.getRequestBody().readAllBytes();
                    exchange.getResponseHeaders().set("Location", "/elsewhere");
                    exchange.sendResponseHeaders(status, -1);
                    exchange.close();
                });
        server.start();
        try {
            assertThatThrownBy(() -> client(server).complete("JSON", "fixture", 0, 300))
                    .isInstanceOf(UpstreamServiceException.class);
            assertThat(count).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    private DeepSeekChatClient client(HttpServer server) {
        return new DeepSeekChatClient(
                "synthetic",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "fixture",
                "disabled",
                2,
                json,
                null,
                cn.edu.whu.zhizhida.ai.AiGuardFixtures.guard(),
                BusinessMetrics.disabled());
    }
}
