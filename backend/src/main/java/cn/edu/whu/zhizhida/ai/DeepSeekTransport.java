package cn.edu.whu.zhizhida.ai;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import cn.edu.whu.zhizhida.common.UpstreamServiceException;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** JSON 与工具调用共用传输层；此处不重试、不重定向，也不执行工具。 */
final class DeepSeekTransport {
    private final HttpClient http;
    private final BusinessMetrics metrics;

    DeepSeekTransport(HttpClient http, BusinessMetrics metrics) {
        this.http = http;
        this.metrics = metrics;
    }

    static HttpClient client(Duration connectTimeout) {
        return HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    static ObjectNode body(ObjectMapper json, String model, String thinking, int maxTokens) {
        var body = json.createObjectNode().put("model", model).put("stream", false).put("max_tokens", maxTokens);
        if (thinking != null && !thinking.isBlank())
            body.set("thinking", json.createObjectNode().put("type", thinking));
        return body;
    }

    /** SDK 边界将完整响应体同时纳入字节限制和总超时。 */
    String boundedExchange(HttpRequest request) throws IOException, InterruptedException {
        long started = System.nanoTime();
        var future = http.sendAsync(request, info -> new DeepSeekJsonSubscriber(info.statusCode()));
        try {
            var response = future.get(request.timeout().orElseThrow().toNanos(), TimeUnit.NANOSECONDS);
            int status = response.statusCode();
            if (status < 200 || status >= 300) throw UpstreamServiceException.coded("MODEL_HTTP_" + status);
            metrics.record(BusinessMetrics.Operation.AI_HTTP, BusinessMetrics.Outcome.SUCCESS, System.nanoTime() - started);
            return response.body();
        } catch (TimeoutException ex) {
            future.cancel(true);
            metrics.record(BusinessMetrics.Operation.AI_HTTP, BusinessMetrics.Outcome.FAILURE, System.nanoTime() - started);
            throw new java.net.http.HttpTimeoutException("Model response deadline exceeded");
        } catch (ExecutionException ex) {
            metrics.record(BusinessMetrics.Operation.AI_HTTP, BusinessMetrics.Outcome.FAILURE, System.nanoTime() - started);
            if (ex.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IOException("Model response failed", ex.getCause());
        } catch (InterruptedException | RuntimeException ex) {
            future.cancel(true);
            metrics.record(BusinessMetrics.Operation.AI_HTTP, BusinessMetrics.Outcome.FAILURE, System.nanoTime() - started);
            throw ex;
        }
    }
}
