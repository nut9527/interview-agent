package compatibility;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** 使用真实的本机 HTTP 与合成 Prompt，不读取环境变量或应用配置。 */
final class ModelHttpFixture implements AutoCloseable {
    interface Responder { void respond(HttpExchange exchange) throws IOException; }
    record Request(String attempt, String body) { }
    final HttpServer server;
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final List<Request> requests = new CopyOnWriteArrayList<>();
    final AtomicInteger disconnected = new AtomicInteger();
    volatile int status = 200;
    volatile String contentType = "application/json";
    volatile String response = envelope("{\"role\":\"assistant\",\"content\":\"{}\"}", "stop", "null");
    volatile Responder responder = this::respond;

    ModelHttpFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/chat/completions", exchange -> {
            requests.add(new Request(exchange.getRequestHeaders().getFirst("X-Client-Request-Id"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            try (exchange) { responder.respond(exchange); }
            catch (IOException ignored) { disconnected.incrementAndGet(); }
        });
        server.start();
    }

    String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    void respond(HttpExchange exchange) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Location", baseUrl() + "/chat/completions");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    static String envelope(String message, String finish, String usage) {
        return "{\"id\":\"fixture\",\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"finish_reason\":\""
                + finish + "\",\"message\":" + message + "}],\"usage\":" + usage + "}";
    }

    static String chunk(String delta, String finish) {
        return "data: {\"id\":\"fixture\",\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"delta\":"
                + delta + ",\"finish_reason\":" + (finish == null ? "null" : "\"" + finish + "\"") + "}]}\n\n";
    }

    @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
