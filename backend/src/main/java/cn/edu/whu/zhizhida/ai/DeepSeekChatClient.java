package cn.edu.whu.zhizhida.ai;

import cn.edu.whu.zhizhida.ai.api.JsonChatResponse;
import cn.edu.whu.zhizhida.ai.api.ModelUsage;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * JSON 对话的供应商适配器，负责请求预算、HTTP 通信、完成状态与响应结构校验。
 * 通过 AiCallGuard 共享并发和熔断保护；业务字段是否可用仍由各业务适配器判断。
 */
@Component
public class DeepSeekChatClient implements cn.edu.whu.zhizhida.ai.api.JsonChatClient {

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final String thinking;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AiCallGuard callGuard;
    private final BusinessMetrics metrics;

    @Autowired
    public DeepSeekChatClient(
            @Value("${ai.api-key:}") String apiKey,
            @Value("${ai.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${ai.deepseek.model:deepseek-chat}") String model,
            @Value("${ai.deepseek.thinking:}") String thinking,
            @Value("${ai.deepseek.timeout-seconds:30}") long timeoutSeconds,
            ObjectMapper objectMapper,
            AiCallGuard callGuard,
            BusinessMetrics metrics
    ) {
        this(apiKey, baseUrl, model, thinking, timeoutSeconds, objectMapper, null, callGuard, metrics);
    }

    DeepSeekChatClient(String apiKey,
                       String baseUrl,
                       String model,
                       String thinking,
                       long timeoutSeconds,
                       ObjectMapper objectMapper,
                       HttpClient httpClient) {
        this(apiKey, baseUrl, model, thinking, timeoutSeconds, objectMapper, httpClient,
                new AiCallGuard(4, 3, 30));
    }

    DeepSeekChatClient(String apiKey, String baseUrl, String model, String thinking,
                       long timeoutSeconds, ObjectMapper objectMapper, HttpClient httpClient,
                       AiCallGuard callGuard) {
        this(apiKey, baseUrl, model, thinking, timeoutSeconds, objectMapper, httpClient, callGuard, BusinessMetrics.disabled());
    }

    DeepSeekChatClient(String apiKey, String baseUrl, String model, String thinking,
                       long timeoutSeconds, ObjectMapper objectMapper, HttpClient httpClient,
                       AiCallGuard callGuard, BusinessMetrics metrics) {
        this.apiKey = apiKey;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.model = model;
        this.thinking = thinking;
        this.timeout = Duration.ofSeconds(Math.max(timeoutSeconds, 1));
        this.objectMapper = objectMapper;
        this.callGuard = callGuard;
        this.metrics = metrics;
        this.httpClient = httpClient == null
                ? HttpClient.newBuilder().connectTimeout(this.timeout).build()
                : httpClient;
    }

    @Override
    public JsonChatResponse complete(String systemPrompt,
                                 String userPrompt,
                                 double temperature,
                                 int maxTokens) {
        if (systemPrompt == null || systemPrompt.length() > 8000
                || userPrompt == null || userPrompt.length() > 32000
                || maxTokens < 1 || maxTokens > 2000) {
            throw new IllegalArgumentException("AI 输入或输出预算超过上限");
        }
        requireApiKey();
        return callGuard.execute(() -> send(systemPrompt, userPrompt, temperature, maxTokens));
    }

    private JsonChatResponse send(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
        try {
            HttpRequest request = buildRequest(systemPrompt, userPrompt, temperature, maxTokens);
            HttpResponse<String> response = exchange(request);
            return parsePayload(response.body());
        } catch (HttpTimeoutException e) {
            throw new IllegalStateException("DeepSeek API 调用超时，请稍后重试。", e);
        } catch (IOException e) {
            throw new IllegalStateException("DeepSeek API 网络或响应解析失败，请稍后重试。", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DeepSeek API 调用被中断，请重试。", e);
        }
    }

    private HttpResponse<String> exchange(HttpRequest request) throws IOException, InterruptedException {
        long started = System.nanoTime();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            ensureSuccessful(response);
            metrics.record(BusinessMetrics.Operation.AI_HTTP, BusinessMetrics.Outcome.SUCCESS, System.nanoTime() - started);
            return response;
        } catch (IOException | InterruptedException | RuntimeException ex) {
            metrics.record(BusinessMetrics.Operation.AI_HTTP, BusinessMetrics.Outcome.FAILURE, System.nanoTime() - started);
            throw ex;
        }
    }

    private HttpRequest buildRequest(String systemPrompt,
                                     String userPrompt,
                                     double temperature,
                                     int maxTokens) throws IOException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);

        if (thinking != null && !thinking.isBlank()) {
            ObjectNode thinkingNode = objectMapper.createObjectNode();
            thinkingNode.put("type", thinking);
            body.set("thinking", thinkingNode);
        }

        ObjectNode responseFormat = objectMapper.createObjectNode();
        responseFormat.put("type", "json_object");
        body.set("response_format", responseFormat);

        ArrayNode messages = objectMapper.createArrayNode();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrompt));
        body.set("messages", messages);

        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body),
                        StandardCharsets.UTF_8
                ))
                .build();
    }

    private ObjectNode message(String role, String content) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private void ensureSuccessful(HttpResponse<String> response) {
        int statusCode = response.statusCode();
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }
        if (statusCode == 401 || statusCode == 403) {
            throw new IllegalStateException("DeepSeek API 鉴权失败，请检查 DEEPSEEK_API_KEY。" );
        }
        if (statusCode == 429) {
            throw new IllegalStateException("DeepSeek API 请求受限，请检查余额或稍后重试。" );
        }
        throw new IllegalStateException("DeepSeek API 调用失败：HTTP " + statusCode + "，请稍后重试。" );
    }

    private JsonChatResponse parsePayload(String responseBody) throws IOException {
        JsonNode response = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(responseBody);
        if (response == null || !response.isObject()) {
            throw new IllegalStateException("DeepSeek API 响应格式无效，请重试。");
        }
        ModelUsage usage = parseUsage(response.path("usage"));
        // 即使后续生成状态或业务字段校验失败，也记录已获得的模型令牌用量。
        metrics.modelUsage(usage);
        JsonNode firstChoice = response.path("choices").path(0);
        // 即使内容看似可解析，截断或异常结束的生成也不能当作完整业务结果返回。
        if ("length".equals(firstChoice.path("finish_reason").asText())) {
            throw new cn.edu.whu.zhizhida.common.InvalidModelOutputException("DeepSeek API 输出被截断，请重试。" );
        }
        if (!"stop".equals(firstChoice.path("finish_reason").asText())) {
            throw new IllegalStateException("DeepSeek API 未正常完成生成，请重试。");
        }
        JsonNode contentNode = firstChoice.path("message").path("content");
        String content = contentNode.isTextual() ? contentNode.textValue().trim() : "";
        if (content.isBlank()) {
            throw new cn.edu.whu.zhizhida.common.InvalidModelOutputException("DeepSeek API 没有返回可用内容，请重试。" );
        }
        JsonNode payload;
        try { payload = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(extractJson(content)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new cn.edu.whu.zhizhida.common.InvalidModelOutputException("DeepSeek API 模型正文响应解析失败，必须返回完整 JSON 对象。");
        }
        if (payload == null || !payload.isObject()) {
            throw new cn.edu.whu.zhizhida.common.InvalidModelOutputException("DeepSeek API 必须返回 JSON 对象，请重试。");
        }
        return new JsonChatResponse(payload, usage);
    }

    private ModelUsage parseUsage(JsonNode node) {
        // 用量缺失或非法均保留为未知，不能将其记成零消耗。
        if (node.isMissingNode() || node.isNull()) return ModelUsage.unknown(ModelUsage.Status.MISSING);
        JsonNode input = node.path("prompt_tokens"), output = node.path("completion_tokens"), total = node.path("total_tokens");
        if (!input.isIntegralNumber() || !output.isIntegralNumber() || !total.isIntegralNumber()
                || !input.canConvertToLong() || !output.canConvertToLong() || !total.canConvertToLong()) {
            return ModelUsage.unknown(ModelUsage.Status.INVALID);
        }
        try {
            return new ModelUsage(ModelUsage.Status.REPORTED, input.longValue(), output.longValue(), total.longValue());
        } catch (IllegalArgumentException ex) {
            return ModelUsage.unknown(ModelUsage.Status.INVALID);
        }
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
            throw new IllegalStateException(
                    "DeepSeek API Key 未配置。请设置环境变量 DEEPSEEK_API_KEY 或 AI_API_KEY。"
            );
        }
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "https://api.deepseek.com";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
