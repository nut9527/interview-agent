package cn.edu.whu.zhizhida.embedding;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import tools.jackson.databind.json.JsonMapper;

/** CPU-only local model. No document storage, request logging or remote inference. */
public final class EmbeddingServer implements AutoCloseable {
    public static final String MODEL = "bge-small-zh-v1.5-q";
    public static final String SERVICE = "zhizhida-local-embedding-v1";
    public static final int DIMENSION = 512;
    private static final int MAX_BODY_BYTES = 128 * 1024;
    private final JsonMapper json = new JsonMapper();
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore inference = new Semaphore(1);
    private final Function<List<String>, List<float[]>> embed;

    EmbeddingServer(int port, Function<List<String>, List<float[]>> embed) throws IOException {
        this("127.0.0.1", port, embed);
    }

    EmbeddingServer(String host, int port, Function<List<String>, List<float[]>> embed) throws IOException {
        this.embed = embed;
        server = HttpServer.create(new InetSocketAddress(host, port), 16);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public void start() { server.start(); }
    int port() { return server.getAddress().getPort(); }
    @Override public void close() { server.stop(1); executor.shutdownNow(); }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            // Browser requests are not clients of this local backend-only endpoint.
            if (exchange.getRequestHeaders().containsKey("Origin")) { reply(exchange, 403, Map.of("error", "Browser requests are not allowed")); return; }
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/health") && exchange.getRequestMethod().equals("GET")) {
                reply(exchange, 200, Map.of("status", "UP", "service", SERVICE, "model", MODEL, "dimension", DIMENSION)); return;
            }
            if (!path.equals("/v1/embeddings")) { reply(exchange, 404, Map.of("error", "Unknown endpoint")); return; }
            if (!exchange.getRequestMethod().equals("POST")) { reply(exchange, 405, Map.of("error", "POST required")); return; }
            if (!inference.tryAcquire()) { reply(exchange, 503, Map.of("error", "Embedding worker busy")); return; }
            try {
                byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
                if (bytes.length > MAX_BODY_BYTES) { reply(exchange, 413, Map.of("error", "Request too large")); return; }
                var input = json.readTree(bytes);
                if (!input.path("model").asString().equals(MODEL)
                        || input.has("dimensions") && input.path("dimensions").asInt() != DIMENSION
                        || input.has("encoding_format") && !input.path("encoding_format").asString().equals("float"))
                    throw new IllegalArgumentException();
                var values = input.path("input");
                var texts = new ArrayList<String>();
                if (values.isString()) texts.add(values.asString());
                else if (values.isArray()) {
                    for (var value : values) {
                        if (!value.isString()) throw new IllegalArgumentException();
                        texts.add(value.asString());
                    }
                } else throw new IllegalArgumentException();
                if (texts.isEmpty() || texts.size() > 16 || texts.stream().anyMatch(t -> t.isBlank() || t.length() > 4000))
                    throw new IllegalArgumentException();
                var embeddings = embed.apply(List.copyOf(texts));
                if (embeddings.size() != texts.size()) throw new IllegalStateException("Invalid model batch");
                var data = new ArrayList<Map<String, Object>>();
                for (int i = 0; i < embeddings.size(); i++) {
                    if (embeddings.get(i).length != DIMENSION) throw new IllegalStateException("Invalid model dimension");
                    data.add(Map.of("object", "embedding", "index", i, "embedding", embeddings.get(i)));
                }
                // Usage is intentionally omitted rather than fabricating token counts.
                reply(exchange, 200, Map.of("object", "list", "model", MODEL, "data", data));
            } catch (IllegalArgumentException | tools.jackson.core.JacksonException ex) {
                reply(exchange, 400, Map.of("error", "Expected model, optional dimensions=512 and 1..16 nonempty text inputs of at most 4000 characters"));
            } catch (RuntimeException ex) {
                // Do not log user text or upstream exceptions that can contain it.
                reply(exchange, 500, Map.of("error", "Local embedding failed"));
            } finally { inference.release(); }
        }
    }

    private void reply(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    public static void main(String[] args) throws Exception {
        String host = System.getProperty("embedding.host", "127.0.0.1");
        int port = Integer.parseInt(System.getProperty("embedding.port", "18092"));
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("Invalid local embedding port");
        var model = new BgeSmallZhV15QuantizedEmbeddingModel();
        // Load model and native runtime before publishing readiness.
        if (model.embed("准备本地语义检索").content().vector().length != DIMENSION)
            throw new IllegalStateException("Unexpected BGE dimension");
        var service = new EmbeddingServer(host, port, texts -> model.embedAll(texts.stream().map(TextSegment::from).toList())
                .content().stream().map(e -> e.vector()).toList());
        Runtime.getRuntime().addShutdownHook(new Thread(service::close));
        service.start();
        System.out.println(SERVICE + " ready at " + host + ":" + port + " model=" + MODEL + " dimension=" + DIMENSION);
    }
}
