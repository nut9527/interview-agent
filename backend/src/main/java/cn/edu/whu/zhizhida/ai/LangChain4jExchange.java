package cn.edu.whu.zhizhida.ai;

import cn.edu.whu.zhizhida.ai.api.ToolCallingChatClient;
import cn.edu.whu.zhizhida.common.UpstreamServiceException;
import cn.edu.whu.zhizhida.common.execution.RunFailureException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.http.client.*;
import dev.langchain4j.http.client.sse.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 每个持久 Attempt 仅调用一次 SDK；不引入执行器、工具回调、记忆或重试循环。
 * HTTP SPI 复用有界传输；原始历史和用量仍由应用保存。
 * 调用状态不使用单例或 ThreadLocal，因为 HTTP 回调可能切换线程。
 */
final class LangChain4jExchange<T> implements HttpClientBuilder, HttpClient {
    private final ObjectMapper json;
    private final DeepSeekTransport transport;
    private final ObjectNode original;
    private final Duration timeout;
    private final String requestId;
    private final java.util.function.Function<String, T> decode;
    private final AtomicBoolean sent = new AtomicBoolean();
    private String rawResponse;
    private T response;
    private RuntimeException failure;

    LangChain4jExchange(ObjectMapper json, DeepSeekTransport transport, ObjectNode body,
            ToolCallingChatClient.Request request,
            java.util.function.Function<String, T> decode) {
        this(json, transport, body, request.timeout(), request.attemptId(), decode);
    }

    LangChain4jExchange(ObjectMapper json, DeepSeekTransport transport, ObjectNode body,
            Duration timeout, String requestId,
            java.util.function.Function<String, T> decode) {
        this.json = json;
        this.transport = transport;
        this.original = body.deepCopy();
        this.timeout = timeout;
        this.requestId = requestId;
        this.decode = decode;
    }

    T execute(String url, String key) {
        var chat = ChatRequest.builder().messages(messages()).toolSpecifications(tools()).build();
        try {
            OpenAiChatModel.builder().baseUrl(url).apiKey(key).modelName(original.path("model").asText())
                    .maxTokens(original.path("max_tokens").asInt()).timeout(timeout).maxRetries(0)
                    .httpClientBuilder(this).customHeaders(Map.of("X-Client-Request-Id", requestId))
                    .build().chat(chat);
        } catch (RuntimeException ex) {
            throw failure != null ? failure : safeFailure(ex);
        }
        if (failure != null) throw failure;
        if (rawResponse == null) throw UpstreamServiceException.coded("MODEL_PROTOCOL");
        return response;
    }

    private List<ChatMessage> messages() {
        var result = new ArrayList<ChatMessage>();
        for (var message : original.path("messages")) {
            String content = message.path("content").isTextual() ? message.path("content").asText() : null;
            result.add(switch (message.path("role").asText()) {
                case "system" -> SystemMessage.from(content);
                case "user" -> UserMessage.from(content);
                case "tool" -> ToolExecutionResultMessage.from(message.path("tool_call_id").asText(), null, content);
                case "assistant" -> {
                    var calls = new ArrayList<ToolExecutionRequest>();
                    for (var call : message.path("tool_calls")) calls.add(ToolExecutionRequest.builder()
                            .id(call.path("id").asText()).name(call.at("/function/name").asText())
                            .arguments(call.at("/function/arguments").asText()).build());
                    yield AiMessage.builder().text(content).toolExecutionRequests(calls)
                            .thinking(message.path("reasoning_content").asText(null)).build();
                }
                default -> throw new RunFailureException("MODEL_PROTOCOL");
            });
        }
        return result;
    }

    private List<ToolSpecification> tools() {
        var result = new ArrayList<ToolSpecification>();
        for (var tool : original.path("tools")) {
            var function = tool.path("function");
            var parameters = function.path("parameters");
            var schema = JsonObjectSchema.builder();
            parameters.path("properties").properties().forEach(property ->
                    schema.addProperty(property.getKey(), JsonRawSchema.from(property.getValue().toString())));
            var required = new ArrayList<String>();
            parameters.path("required").forEach(value -> required.add(value.asText()));
            schema.required(required);
            if (parameters.has("additionalProperties")) schema.additionalProperties(parameters.path("additionalProperties").asBoolean());
            result.add(ToolSpecification.builder().name(function.path("name").asText())
                    .description(function.path("description").asText(null)).parameters(schema.build()).build());
        }
        return result;
    }

    private java.net.http.HttpRequest prepare(HttpRequest input) {
        if (!List.of(requestId).equals(input.headers().get("X-Client-Request-Id"))
                || !sent.compareAndSet(false, true)) throw new RunFailureException("MODEL_ATTEMPT_INVALID");
        var body = (ObjectNode) json.readTree(input.body());
        // SDK 的类型化消息和 Schema 尚不能完整保留供应商扩展与约束。
        for (String field : List.of("messages", "tools", "thinking", "temperature", "tool_choice", "response_format")) {
            if (original.has(field)) body.set(field, original.get(field)); else body.remove(field);
        }
        String payload = body.toString();
        if (payload.length() > 160_000) throw new RunFailureException("MODEL_INPUT_BUDGET");
        var builder = java.net.http.HttpRequest.newBuilder(java.net.URI.create(input.url())).timeout(timeout);
        input.headers().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        return builder.POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload)).build();
    }

    public SuccessfulHttpResponse execute(HttpRequest input) {
        try {
            rawResponse = transport.boundedExchange(prepare(input));
            response = decode.apply(rawResponse);
            return SuccessfulHttpResponse.builder().statusCode(200).body(sdkProjection(rawResponse).toString()).build();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw remember(UpstreamServiceException.coded("MODEL_INTERRUPTED", ex));
        } catch (IOException ex) {
            throw remember(UpstreamServiceException.coded("MODEL_NETWORK", ex));
        } catch (RuntimeException ex) { throw remember(safeFailure(ex)); }
    }

    public void execute(HttpRequest input, ServerSentEventParser parser, ServerSentEventListener listener) {
        // SDK 的 HTTP SPI 要求该入口；应用只支持完整 JSON 响应。
        throw new UnsupportedOperationException("Streaming model requests are not supported");
    }

    private ObjectNode sdkProjection(String raw) {
        // SDK 的 int 用量可能强制转换字符串或溢出，以原始 DeepSeekUsage 为准。
        // 仅从 SDK 副本中移除该字段，原始响应仍由应用解码。
        var value = json.reader().with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
        if (!(value instanceof ObjectNode object)) throw UpstreamServiceException.coded("MODEL_PROTOCOL");
        object.remove("usage");
        return object;
    }
    private RuntimeException remember(RuntimeException error) { failure = error; return error; }
    private RuntimeException safeFailure(Throwable error) {
        if (failure != null) return failure;
        if (error instanceof UpstreamServiceException upstream) return upstream;
        if (error instanceof RunFailureException run) return run;
        return UpstreamServiceException.coded("MODEL_PROTOCOL");
    }
    public Duration connectTimeout() { return timeout; }
    public HttpClientBuilder connectTimeout(Duration ignored) { return this; }
    public Duration readTimeout() { return timeout; }
    public HttpClientBuilder readTimeout(Duration ignored) { return this; }
    public HttpClient build() { return this; }
}
