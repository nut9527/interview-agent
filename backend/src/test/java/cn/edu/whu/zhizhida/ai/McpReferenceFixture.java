package cn.edu.whu.zhizhida.ai;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.json.JsonMapper;

/** 为持久化 MCP 集成测试提供共享的合成 Streamable HTTP 服务。 */
public final class McpReferenceFixture implements AutoCloseable {
    private final HttpServer server;
    public final AtomicInteger calls = new AtomicInteger();
    public McpReferenceFixture() {
        try { server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); }
        catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
        var json = new JsonMapper();
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
                case "tools/list" -> result.putArray("tools").addObject().put("name", "reference_search").put("description", "fixture")
                        .set("inputSchema", json.readTree("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"topK\":{\"type\":\"integer\"}},\"required\":[\"query\",\"topK\"],\"additionalProperties\":false}"));
                case "tools/call" -> {
                    calls.incrementAndGet();
                    result.put("isError", false).putArray("content").addObject().put("type", "text").put("text", "合成缓存参考");
                }
                default -> { response.remove("result"); response.putObject("error").put("code", -32601).put("message", "Unknown method"); }
            }
            var bytes = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
    }
    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"; }
    public void close() { server.stop(0); }
}
