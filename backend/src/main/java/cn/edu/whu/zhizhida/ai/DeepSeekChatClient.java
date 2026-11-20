package cn.edu.whu.zhizhida.ai;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import cn.edu.whu.zhizhida.ai.api.JsonChatClient;
import cn.edu.whu.zhizhida.ai.api.JsonChatResponse;
import cn.edu.whu.zhizhida.common.InvalidModelOutputException;
import cn.edu.whu.zhizhida.common.UpstreamServiceException;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * JSON 对话的供应商适配器，负责请求预算、HTTP 通信、完成状态与响应结构校验。 通过 AiCallGuard
 * 共享并发和熔断保护；业务字段是否可用仍由各业务适配器判断。
 */
@Component
public class DeepSeekChatClient implements JsonChatClient {
    private static final int LONG_RESPONSE_TOKEN_THRESHOLD = 2000;
    private static final Duration LONG_RESPONSE_MIN_TIMEOUT = Duration.ofSeconds(50);

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final String thinking;
    private final Duration timeout;
    private final DeepSeekTransport transport;
    private final ObjectMapper objectMapper;
    private final AiCallGuard callGuard;
    private final BusinessMetrics metrics;

    @Autowired
    public DeepSeekChatClient(
            @Value("${ai.api-key:}") String apiKey,
            @Value("${ai.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${ai.deepseek.model:deepseek-flash}") String model,
            @Value("${ai.deepseek.thinking:}") String thinking,
            @Value("${ai.deepseek.timeout-seconds:30}") long timeoutSeconds,
            ObjectMapper objectMapper,
            AiCallGuard callGuard,
            BusinessMetrics metrics) {
        this(
                apiKey,
                baseUrl,
                model,
                thinking,
                timeoutSeconds,
                objectMapper,
                null,
                callGuard,
                metrics);
    }

    DeepSeekChatClient(
            String apiKey,
            String baseUrl,
            String model,
            String thinking,
            long timeoutSeconds,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            AiCallGuard callGuard) {
        this(
                apiKey,
                baseUrl,
                model,
                thinking,
                timeoutSeconds,
                objectMapper,
                httpClient,
                callGuard,
                BusinessMetrics.disabled());
    }

    DeepSeekChatClient(
            String apiKey,
            String baseUrl,
            String model,
            String thinking,
            long timeoutSeconds,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            AiCallGuard callGuard,
            BusinessMetrics metrics) {
        this.apiKey = apiKey;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.model = model;
        this.thinking = thinking;
        this.timeout = Duration.ofSeconds(Math.max(timeoutSeconds, 1));
        this.objectMapper = objectMapper;
        this.callGuard = callGuard;
        this.metrics = metrics;
        this.transport = new DeepSeekTransport(
                httpClient == null ? DeepSeekTransport.client(this.timeout) : httpClient,
                metrics);
    }

    @Override
    public JsonChatResponse complete(
            String systemPrompt, String userPrompt, double temperature, int maxTokens) {
        return complete(systemPrompt, userPrompt, temperature, maxTokens, thinking);
    }

    @Override
    public JsonNode completeJsonDirectly(
            String systemPrompt, String userPrompt, double temperature, int maxTokens) {
        return complete(systemPrompt, userPrompt, temperature, maxTokens, "disabled").payload();
    }

    private JsonChatResponse complete(
            String systemPrompt,
            String userPrompt,
            double temperature,
            int maxTokens,
            String requestThinking) {
        if (systemPrompt == null
                || systemPrompt.length() > 8000
                || userPrompt == null
                || userPrompt.length() > 32000
                || maxTokens < 1
                || maxTokens > 8192) {
            throw new IllegalArgumentException("AI 输入或输出预算超过上限");
        }
        requireApiKey();
        Duration requestTimeout = requestTimeout(maxTokens);
        return callGuard.execute(
                requestTimeout,
                () -> send(
                        buildBody(
                                systemPrompt,
                                userPrompt,
                                temperature,
                                maxTokens,
                                requestThinking),
                        requestTimeout));
    }

    private JsonChatResponse send(ObjectNode body, Duration requestTimeout) {
        // 仅关联追踪：独立 JSON 调用不生成持久 Run 的尝试记录。
        return new LangChain4jExchange<>(
                objectMapper,
                transport,
                body,
                requestTimeout,
                "json-" + java.util.UUID.randomUUID(),
                this::parsePayload)
                .execute(baseUrl, apiKey);
    }

    private Duration requestTimeout(int maxTokens) {
        // 长篇出题保留生成时间；其他现有调用继续使用原超时配置。
        if (maxTokens > LONG_RESPONSE_TOKEN_THRESHOLD
                && timeout.compareTo(LONG_RESPONSE_MIN_TIMEOUT) < 0) {
            return LONG_RESPONSE_MIN_TIMEOUT;
        }
        return timeout;
    }

    private ObjectNode buildBody(
            String systemPrompt,
            String userPrompt,
            double temperature,
            int maxTokens,
            String requestThinking) {
        ObjectNode body = DeepSeekTransport.body(objectMapper, model, requestThinking, maxTokens);
        body.put("temperature", temperature);

        ObjectNode responseFormat = objectMapper.createObjectNode();
        responseFormat.put("type", "json_object");
        body.set("response_format", responseFormat);

        ArrayNode messages = objectMapper.createArrayNode();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrompt));
        body.set("messages", messages);

        return body;
    }

    private ObjectNode message(String role, String content) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private JsonChatResponse parsePayload(String responseBody) {
        var response = DeepSeekResponseReader.read(objectMapper, metrics, responseBody);
        if (!"stop".equals(response.finishReason()))
            throw UpstreamServiceException.coded("MODEL_PROTOCOL");
        String content = response.message().path("content").asString().trim();
        JsonNode payload;
        try {
            payload = objectMapper
                    .reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(extractJson(content));
        } catch (tools.jackson.core.JacksonException ex) {
            throw new InvalidModelOutputException(
                    InvalidModelOutputException.Reason.INVALID_JSON,
                    "DeepSeek API 模型正文响应解析失败，必须返回完整 JSON 对象。");
        }
        if (payload == null || !payload.isObject()) {
            throw new InvalidModelOutputException(
                    InvalidModelOutputException.Reason.NON_OBJECT,
                    "DeepSeek API 必须返回 JSON 对象，请重试。");
        }
        return new JsonChatResponse(payload, response.usage());
    }

    private String extractJson(String content) {
        if (content.startsWith("```") && content.endsWith("```")) {
            int firstLine = content.indexOf('\n');
            if (firstLine >= 0) {
                return content.substring(firstLine + 1, content.length() - 3).trim();
            }
        }
        return content;
    }

    private void requireApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw UpstreamServiceException.coded("MODEL_CONFIGURATION");
        }
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "https://api.deepseek.com";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
