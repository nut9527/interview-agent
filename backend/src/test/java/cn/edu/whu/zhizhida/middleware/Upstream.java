package cn.edu.whu.zhizhida.middleware;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.json.JsonMapper;

class Upstream implements AutoCloseable {
    final JsonMapper json = new JsonMapper();
    final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    final AtomicInteger active = new AtomicInteger(),
            peak = new AtomicInteger(),
            sends = new AtomicInteger();
    final HttpServer server;
    volatile CountDownLatch gate = new CountDownLatch(0);
    volatile CountDownLatch workerCalls = new CountDownLatch(0);
    volatile CountDownLatch apiCalls = new CountDownLatch(0);

    Upstream() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(threads);
        server.createContext(
                "/chat/completions",
                exchange -> {
                    int current = active.incrementAndGet();
                    peak.accumulateAndGet(current, Math::max);
                    sends.incrementAndGet();
                    try {
                        var body = json.readTree(exchange.getRequestBody().readAllBytes());
                        assertThat(body.path("stream").asBoolean()).isFalse();
                        String system = body.at("/messages/0/content").asString();
                        boolean resume = system.contains("你是简历编辑");
                        boolean finalPhase = system.contains("当前处于 FINAL 阶段");
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, 0);
                        if (finalPhase) {
                            workerCalls.countDown();
                            if (!gate.await(20, TimeUnit.SECONDS))
                                throw new IOException(
                                        "Worker capacity verification exceeded bound");
                            Thread.sleep(600); // 单 Worker 与双 Worker 使用相同的有界延迟，保证比较条件一致。
                        } else if (resume) {
                            apiCalls.countDown();
                            if (!gate.await(20, TimeUnit.SECONDS))
                                throw new IOException("API capacity verification exceeded bound");
                        }
                        var response = json.createObjectNode();
                        response.putArray("choices")
                                .addObject()
                                .put("finish_reason", "stop")
                                .putObject("message")
                                .put("role", "assistant")
                                .put("reasoning_content", "PRIVATE_REASONING_SENTINEL")
                                .put(
                                        "content",
                                        resume
                                                ? "{\"changes\":[{\"index\":0,\"text\":\"负责缓存项目。\"}]}"
                                                : finalPhase
                                                        ? "{\"version\":\"phased-v2\",\"phase\":\"FINAL\",\"result\":{\"question\":\"请说明个人职责？\",\"questionCompetencyId\":\"experience\"}}"
                                                        : "{\"version\":\"phased-v2\",\"phase\":\"ACTION\",\"action\":\"COMPLETE\",\"reason\":\"已有资料\"}");
                        exchange.getResponseBody()
                                .write(response.toString().getBytes(StandardCharsets.UTF_8));
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        active.decrementAndGet();
                        exchange.close();
                    }
                });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void reset() {
        assertThat(active.get()).isZero();
        peak.set(0);
        sends.set(0);
    }

    @Override
    public void close() {
        gate.countDown();
        server.stop(0);
        threads.shutdownNow();
    }
}
