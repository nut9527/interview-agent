package cn.edu.whu.zhizhida.embedding;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EmbeddingServerTest {
    final HttpClient http = HttpClient.newHttpClient();
    final JsonMapper json = new JsonMapper();

    @Test void handlesOpenAiStringAndBatchInputsInOrderWithFixedDimension() throws Exception {
        var calls = new AtomicInteger();
        try (var service = new EmbeddingServer(0, texts -> {
            calls.incrementAndGet();
            return texts.stream().map(text -> { var v = new float[512]; v[0] = text.length(); return v; }).toList();
        })) {
            service.start();
            var response = post(service, "{\"model\":\"bge-small-zh-v1.5-q\",\"input\":[\"缓存\",\"缓存一致性\"],\"dimensions\":512,\"encoding_format\":\"float\"}");
            assertEquals(200, response.statusCode());
            var data = json.readTree(response.body()).path("data");
            assertEquals(2, data.size());
            assertEquals(512, data.get(0).path("embedding").size());
            assertEquals(2, data.get(0).path("embedding").get(0).asInt());
            assertEquals(5, data.get(1).path("embedding").get(0).asInt());
            assertEquals(200, post(service, "{\"model\":\"bge-small-zh-v1.5-q\",\"input\":\"缓存\"}").statusCode());
            assertEquals(2, calls.get());
        }
    }

    @Test void rejectsInvalidProtocolBeforeInferenceAndReportsIdentity() throws Exception {
        try (var service = new EmbeddingServer(0, texts -> { fail("Invalid request reached model"); return List.of(); })) {
            service.start();
            for (String body : List.of("{}", "{", "{\"model\":\"different\",\"input\":\"x\"}",
                    "{\"model\":\"bge-small-zh-v1.5-q\",\"input\":[]}",
                    "{\"model\":\"bge-small-zh-v1.5-q\",\"input\":[1]}",
                    "{\"model\":\"bge-small-zh-v1.5-q\",\"input\":\"x\",\"dimensions\":384}"))
                assertEquals(400, post(service, body).statusCode());
            assertEquals(413, post(service, "x".repeat(128 * 1024 + 1)).statusCode());
            var health = http.send(HttpRequest.newBuilder(uri(service, "/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(EmbeddingServer.SERVICE, json.readTree(health.body()).path("service").asString());
            var browser = http.send(HttpRequest.newBuilder(uri(service, "/health")).header("Origin", "https://example.com").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, browser.statusCode());
        }
    }

    private URI uri(EmbeddingServer service, String path) { return URI.create("http://127.0.0.1:" + service.port() + path); }
    private HttpResponse<String> post(EmbeddingServer service, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(service, "/v1/embeddings")).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
