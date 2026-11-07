package cn.edu.whu.zhizhida.ai;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class LangChain4jReferenceTest {
    @Test void discoversOnlyPinnedSchemaAndExecutesOneReadOnlyCall() throws Exception {
        var json = new JsonMapper(); var calls = new AtomicInteger(); var changed = new AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            if (!exchange.getRequestMethod().equals("POST")) { exchange.sendResponseHeaders(405, -1); exchange.close(); return; }
            var request = json.readTree(exchange.getRequestBody().readAllBytes());
            if (!request.has("id")) { exchange.sendResponseHeaders(202, -1); exchange.close(); return; }
            var response = json.createObjectNode().put("jsonrpc", "2.0"); response.set("id", request.get("id"));
            var result = response.putObject("result");
            switch (request.path("method").asText()) {
                case "initialize" -> {
                    result.put("protocolVersion", "2025-06-18"); result.putObject("capabilities").putObject("tools");
                    result.putObject("serverInfo").put("name", "synthetic-reference").put("version", "1");
                }
                case "tools/list" -> {
                    var tool = result.putArray("tools").addObject().put("name", "reference_search").put("description", "fixture");
                    tool.set("inputSchema", json.readTree("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"topK\":{\"type\":\"integer\"}},\"required\":[\"query\",\"topK\"],\"additionalProperties\":false}"));
                    if (changed.get()) ((tools.jackson.databind.node.ObjectNode) tool.path("inputSchema")).put("additionalProperties", true);
                }
                case "tools/call" -> {
                    calls.incrementAndGet(); assertThat(request.at("/params/name").asText()).isEqualTo("reference_search");
                    result.put("isError", false).putArray("content").addObject().put("type", "text").put("text", "合成缓存参考");
                }
                default -> throw new AssertionError("Unexpected MCP method");
            }
            var bytes = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try (var client = new LangChain4jReferenceClient("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp")) {
            assertThat(client.search("缓存", 1)).contains("合成缓存参考"); assertThat(calls).hasValue(1);
            assertThatThrownBy(() -> client.search("x", 4)).hasMessageContaining("TOOL_ARGUMENTS");
            changed.set(true);
            assertThatThrownBy(() -> client.search("缓存", 1)).hasMessageContaining("MCP_CATALOG_CHANGED");
            assertThat(calls).hasValue(1);
        } finally { server.stop(0); }
    }
    @Test void refusesRemoteEndpointsBeforeConnecting() {
        assertThatThrownBy(() -> new LangChain4jReferenceClient("https://example.com/mcp")).isInstanceOf(IllegalArgumentException.class);
    }
}
