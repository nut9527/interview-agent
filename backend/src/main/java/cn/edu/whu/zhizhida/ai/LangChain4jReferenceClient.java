package cn.edu.whu.zhizhida.ai;

import cn.edu.whu.zhizhida.ai.api.ReferenceToolClient;
import cn.edu.whu.zhizhida.common.execution.RunFailureException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** 只访问本机或 Compose 的只读资料服务，首次检索才建立连接。 */
public final class LangChain4jReferenceClient implements ReferenceToolClient, AutoCloseable {
    static final JsonObjectSchema SCHEMA = JsonObjectSchema.builder().addStringProperty("query")
            .addIntegerProperty("topK").required("query", "topK").additionalProperties(false).build();
    private final String endpoint;
    private McpClient client;
    private final String fingerprint;

    public LangChain4jReferenceClient(String endpoint) {
        var uri = URI.create(endpoint);
        if (!"http".equals(uri.getScheme()) || uri.getHost() == null
                || !List.of("127.0.0.1", "localhost", "[::1]", "mcp-reference").contains(uri.getHost())
                || !"/mcp".equals(uri.getRawPath()) || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Invalid internal MCP reference endpoint");
        this.endpoint = endpoint;
        fingerprint = "mcp-reference-v1:" + java.util.UUID.nameUUIDFromBytes(endpoint.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private synchronized McpClient connection() {
        if (client != null) return client;
        client = DefaultMcpClient.builder().key("local-reference")
                .transport(StreamableHttpMcpTransport.builder().url(endpoint).followRedirects(false)
                        .timeout(Duration.ofSeconds(3)).logRequests(false).logResponses(false).build())
                .protocolVersion("2025-06-18").initializationTimeout(Duration.ofSeconds(3))
                .toolExecutionTimeout(Duration.ofSeconds(3)).autoHealthCheck(false)
                .cacheToolList(false).multiRoundTripMaxRetries(0).logHandler(message -> {}).build();
        return client;
    }
    public String fingerprint() { return fingerprint; }
    private void checkCatalog(McpClient connection) {
        var allowed = connection.listTools();
        if (allowed.size() != 1 || !"reference_search".equals(allowed.getFirst().name())
                || !SCHEMA.equals(allowed.getFirst().parameters()))
            throw new RunFailureException("TOOL_MCP_CATALOG_CHANGED");
    }
    public String search(String query, int topK) {
        if (query == null || query.isBlank() || query.length() > 300 || topK < 1 || topK > 3)
            throw new RunFailureException("TOOL_ARGUMENTS");
        try {
            var connection = connection();
            checkCatalog(connection);
            var args = new JsonMapper().createObjectNode().put("query", query).put("topK", topK);
            var result = connection.executeTool(ToolExecutionRequest.builder().name("reference_search").arguments(args.toString()).build());
            if (result.isError() || result.resultText() == null) throw new RunFailureException("TOOL_MCP_FAILED");
            if (result.resultText().length() > 6000) throw new RunFailureException("TOOL_RESULT_BUDGET");
            return result.resultText();
        } catch (RunFailureException ex) { throw ex; }
        catch (RuntimeException ex) { throw new RunFailureException("TOOL_MCP_UNAVAILABLE"); }
    }
    public synchronized void close() throws Exception {
        if (client != null) try { client.close(); } finally { client = null; }
    }
}
