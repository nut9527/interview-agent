package cn.edu.whu.zhizhida.ai;

import java.time.Duration;
import java.util.HashSet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import cn.edu.whu.zhizhida.ai.api.ToolCallingChatClient;
import cn.edu.whu.zhizhida.common.UpstreamServiceException;
import cn.edu.whu.zhizhida.common.execution.RunFailureException;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 多消息模型适配器；保留助手消息的供应商私有字段，以便携带工具回执继续对话。 */
@Component
public class DeepSeekToolChatClient implements ToolCallingChatClient {
    private static final Duration MAX_REQUEST_TIMEOUT = Duration.ofSeconds(25);
    private static final int MAX_REQUEST_CHARS = 160_000;
    private static final int MAX_OUTPUT_TOKENS = 2_000;

    private final String key;
    private final String url;
    private final Config config;
    private final ObjectMapper json;
    private final AiCallGuard guard;
    private final BusinessMetrics metrics;
    private final DeepSeekTransport transport;
    public DeepSeekToolChatClient(@Value("${ai.api-key:}") String key,
            @Value("${ai.deepseek.base-url:https://api.deepseek.com}") String url,
            @Value("${ai.deepseek.model:deepseek-flash}") String model,
            @Value("${ai.deepseek.thinking:disabled}") String thinking,
            ObjectMapper json, AiCallGuard guard, BusinessMetrics metrics) {
        this.key = key;
        this.url = url.replaceAll("/+$", "");
        this.json = json;
        this.guard = guard;
        this.metrics = metrics;
        this.transport = new DeepSeekTransport(DeepSeekTransport.client(Duration.ofSeconds(10)), metrics);
        this.config = new Config(model, thinking == null || thinking.isBlank() ? "disabled" : thinking,
                "deepseek-langchain4j-v1");
    }

    @Override
    public Config configuration() {
        return config;
    }

    @Override
    public java.util.Map<String, String> executionProfile() {
        // 哈希完整端点以识别代理/地址变更，避免保存 URL 中可能携带的凭证。
        String endpointHash;
        try {
            endpointHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest((url + "/chat/completions").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
        var profile = new java.util.HashMap<>(java.util.Map.of("endpointSha256", endpointHash, "endpointProtocol", "chat/completions",
                "maxOutputTokens", Integer.toString(MAX_OUTPUT_TOKENS),
                "maxRequestChars", Integer.toString(MAX_REQUEST_CHARS),
                "maxTimeoutMillis", Long.toString(MAX_REQUEST_TIMEOUT.toMillis()),
                "temperature", "PROVIDER_DEFAULT", "finalResponseFormat", "json_object"));
        profile.put("modelSdk", LangChain4jVersions.MODEL_SDK);
        profile.put("httpBoundary", "bounded-jdk-v1");
        profile.put("maxResponseBytes", Integer.toString(DeepSeekJsonSubscriber.MAX_BYTES));
        return java.util.Map.copyOf(profile);
    }

    @Override
    public Response complete(Request request) {
        if (key.isBlank()) {
            throw UpstreamServiceException.coded("MODEL_CREDENTIALS_MISSING");
        }
        // 运行使用已冻结的配置，并且单次超时不得突破适配器允许的上限。
        if (!config.equals(request.config()) || request.timeout().isNegative() || request.timeout().isZero()
                || request.timeout().compareTo(MAX_REQUEST_TIMEOUT) > 0) {
            throw new RunFailureException("MODEL_CONFIG_CHANGED");
        }
        if (request.attemptId() == null || !request.attemptId().matches("[A-Za-z0-9_-]{1,128}"))
            throw new RunFailureException("MODEL_ATTEMPT_INVALID");
        return guard.execute(request.timeout(), () -> send(request));
    }

    private Response send(Request request) {
        var body = DeepSeekTransport.body(json, request.config().model(), request.config().thinking(), MAX_OUTPUT_TOKENS);
        body.set("messages", json.valueToTree(request.messages()));
        if (!request.tools().isEmpty()) {
            body.set("tools", json.valueToTree(request.tools()));
            body.put("tool_choice", request.toolsAllowed() ? "auto" : "none");
        }
        // 收束阶段禁止继续调用工具，要求模型返回可供业务校验的 JSON 结果。
        if (!request.toolsAllowed()) {
            body.set("response_format", json.createObjectNode().put("type", "json_object"));
        }
        String payload = body.toString();
        if (payload.length() > MAX_REQUEST_CHARS) {
            throw new RunFailureException("MODEL_INPUT_BUDGET");
        }
        return new LangChain4jExchange<>(json, transport, body, request, this::parse)
                .execute(url, key);
    }

    public Response parse(String text) {
        JsonNode root;
        try {
            root = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
        } catch (JacksonException ex) {
            throw UpstreamServiceException.coded("MODEL_PROTOCOL", ex);
        }
        if (root == null || !root.isObject()) {
            throw UpstreamServiceException.coded("MODEL_PROTOCOL");
        }
        // 即使后续协议校验失败，供应商已报告的用量也应计入监控。
        var usage = DeepSeekUsage.parse(root.path("usage"));
        metrics.modelUsage(usage);
        var choice = root.path("choices").path(0);
        var message = choice.path("message");
        String finish = choice.path("finish_reason").asText();
        if (!message.isObject() || !"assistant".equals(message.path("role").asText())) {
            throw UpstreamServiceException.coded("MODEL_PROTOCOL");
        }
        if ("tool_calls".equals(finish)) {
            validateToolCalls(message.path("tool_calls"));
        } else if (!"stop".equals(finish)) {
            // 仅输出固定内部分类，避免供应商任意文案污染运行错误码。
            throw UpstreamServiceException.coded(switch (finish) {
                case "length" -> "MODEL_FINISH_LENGTH";
                case "content_filter" -> "MODEL_FINISH_CONTENT_FILTER";
                default -> "MODEL_PROTOCOL";
            });
        } else if (!message.path("content").isTextual() || message.path("content").asText().isBlank()
                || hasUnexpectedToolCalls(message.path("tool_calls"))) {
            throw UpstreamServiceException.coded("MODEL_PROTOCOL");
        }
        return new Response(message, finish, usage);
    }

    private void validateToolCalls(JsonNode calls) {
        if (!calls.isArray() || calls.isEmpty()) {
            throw UpstreamServiceException.coded("MODEL_PROTOCOL");
        }
        // 调用编号必须唯一，确保每条工具回执可以准确关联原始调用。
        // 此处仅校验协议结构；工具白名单、参数和访问权限由应用层检查。
        var ids = new HashSet<String>();
        for (var call : calls) {
            var function = call.path("function");
            if (!"function".equals(call.path("type").asText()) || !call.path("id").isTextual()
                    || call.path("id").asText().isBlank() || !ids.add(call.path("id").asText())
                    || !function.path("arguments").isTextual() || !function.path("name").isTextual()
                    || function.path("name").asText().isBlank()) {
                throw UpstreamServiceException.coded("MODEL_PROTOCOL");
            }
        }
    }

    private boolean hasUnexpectedToolCalls(JsonNode calls) {
        return !calls.isMissingNode() && !calls.isNull() && (!calls.isArray() || !calls.isEmpty());
    }

}
