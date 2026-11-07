package compatibility;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventContext;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 仅用于 M0 实验，演示局部 SPI 修正，不可作为生产传输实现。
 * 原始响应校验先于 SDK 类型转换；生产环境须复用现有的有界解码、
 * 持久化调用尝试准入与取消机制，本探针不具备数据库操作权限。
 */
final class BoundaryProbe implements HttpClientBuilder {
    enum Usage { REPORTED, MISSING, INVALID }
    final JsonMapper json = new JsonMapper();
    final HttpClientBuilder delegate;
    final String reservedAttempt;
    final AtomicBoolean sent = new AtomicBoolean();
    final List<JsonNode> deltas = new ArrayList<>();
    JsonNode originalMessages;
    JsonNode originalTools;
    volatile JsonNode rawResponse;
    volatile Usage usage = Usage.MISSING;
    volatile boolean done;
    volatile String finish;

    BoundaryProbe(HttpClientBuilder delegate, String reservedAttempt) {
        this.delegate = delegate;
        this.reservedAttempt = reservedAttempt;
    }

    public Duration connectTimeout() { return delegate.connectTimeout(); }
    public HttpClientBuilder connectTimeout(Duration timeout) { delegate.connectTimeout(timeout); return this; }
    public Duration readTimeout() { return delegate.readTimeout(); }
    public HttpClientBuilder readTimeout(Duration timeout) { delegate.readTimeout(timeout); return this; }

    private HttpRequest prepare(HttpRequest request) {
        if (reservedAttempt == null || !reservedAttempt.matches("[A-Za-z0-9_-]{1,128}")
                || !List.of(reservedAttempt).equals(request.headers().get("X-Client-Request-Id"))
                || !sent.compareAndSet(false, true)) throw new IllegalStateException("MODEL_ATTEMPT_INVALID");
        var body = (ObjectNode) json.readTree(request.body());
        // 恢复应用冻结的原始历史与 schema，避免 SDK 类型进入检查点。
        if (originalMessages != null) body.set("messages", originalMessages.deepCopy());
        if (originalTools != null) body.set("tools", originalTools.deepCopy());
        return HttpRequest.builder().url(request.url()).method(request.method()).headers(request.headers())
                .body(body.toString()).build();
    }

    static Usage classify(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return Usage.MISSING;
        var input = node.path("prompt_tokens");
        var output = node.path("completion_tokens");
        var total = node.path("total_tokens");
        if (!input.isIntegralNumber() || !output.isIntegralNumber() || !total.isIntegralNumber()
                || !input.canConvertToLong() || !output.canConvertToLong() || !total.canConvertToLong()) return Usage.INVALID;
        try {
            return input.longValue() >= 0 && output.longValue() >= 0
                    && Math.addExact(input.longValue(), output.longValue()) == total.longValue()
                    ? Usage.REPORTED : Usage.INVALID;
        } catch (ArithmeticException ex) { return Usage.INVALID; }
    }

    public HttpClient build() {
        var client = delegate.build();
        return new HttpClient() {
            public SuccessfulHttpResponse execute(HttpRequest request) {
                var response = client.execute(prepare(request));
                rawResponse = json.readTree(response.body());
                usage = classify(rawResponse.path("usage"));
                return response;
            }

            public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
                client.execute(prepare(request), parser, new ServerSentEventListener() {
                    private boolean failed;
                    public void onOpen(SuccessfulHttpResponse response) { listener.onOpen(response); }
                    public void onEvent(ServerSentEvent event, ServerSentEventContext context) {
                        if (failed) return;
                        try {
                            if (event.data().equals("[DONE]")) {
                                if (!"stop".equals(finish) && !"tool_calls".equals(finish))
                                    throw new IllegalStateException("MODEL_STREAM_INCOMPLETE");
                                done = true;
                            } else {
                                if (done) throw new IllegalStateException("MODEL_STREAM_PROTOCOL");
                                var chunk = json.readTree(event.data());
                                if (chunk.hasNonNull("usage")) usage = classify(chunk.path("usage"));
                                var choice = chunk.path("choices").path(0);
                                if (choice.hasNonNull("finish_reason")) finish = choice.path("finish_reason").asText();
                                if (choice.has("delta")) deltas.add(choice.path("delta").deepCopy());
                            }
                            listener.onEvent(event, context);
                        } catch (RuntimeException error) {
                            context.parsingHandle().cancel();
                            onError(error);
                        }
                    }
                    public void onError(Throwable error) {
                        if (!failed) { failed = true; listener.onError(error); }
                    }
                    public void onClose() {
                        if (failed) return;
                        if (!done) onError(new IllegalStateException("MODEL_STREAM_INCOMPLETE"));
                        else listener.onClose();
                    }
                });
            }
        };
    }
}
