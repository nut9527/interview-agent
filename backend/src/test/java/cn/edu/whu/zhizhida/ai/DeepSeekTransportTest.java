package cn.edu.whu.zhizhida.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.sun.net.httpserver.HttpServer;

import cn.edu.whu.zhizhida.ai.api.ModelUsage;
import cn.edu.whu.zhizhida.ai.api.ToolCallingChatClient;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import tools.jackson.databind.json.JsonMapper;

class DeepSeekTransportTest {
        final JsonMapper json = new JsonMapper();
        final AtomicInteger sends = new AtomicInteger();
        final CountDownLatch release = new CountDownLatch(1);
        HttpServer server;
        ExecutorService handlers;
        DeepSeekToolChatClient client;
        volatile int status = 200;
        volatile String type = "application/json; charset=utf-8";
        volatile String payload;
        volatile boolean stall;
        volatile String request;
        volatile String attempt;

        @BeforeEach
        void setup() throws Exception {
                handlers = Executors.newVirtualThreadPerTaskExecutor();
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.setExecutor(handlers);
                server.createContext(
                                "/chat/completions",
                                exchange -> {
                                        sends.incrementAndGet();
                                        attempt = exchange.getRequestHeaders().getFirst("X-Client-Request-Id");
                                        request = new String(
                                                        exchange.getRequestBody().readAllBytes(),
                                                        StandardCharsets.UTF_8);
                                        exchange.getResponseHeaders().set("Content-Type", type);
                                        exchange.getResponseHeaders().set("Location", "/chat/completions");
                                        exchange.sendResponseHeaders(status, 0);
                                        try {
                                                // 刻意将每个中文字符的多字节编码拆到不同写入，验证跨块解码。
                                                for (byte b : payload.getBytes(StandardCharsets.UTF_8))
                                                        exchange.getResponseBody().write(b);
                                                exchange.getResponseBody().flush();
                                                if (stall)
                                                        release.await(3, TimeUnit.SECONDS);
                                        } catch (InterruptedException ex) {
                                                Thread.currentThread().interrupt();
                                        } finally {
                                                exchange.close();
                                        }
                                });
                server.start();
                client = createClient("http://127.0.0.1:" + server.getAddress().getPort());
                payload = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"中文\"}}]}";
        }

        DeepSeekToolChatClient createClient(String url) {
                return new DeepSeekToolChatClient(
                                "synthetic-only",
                                url,
                                "fixture",
                                "disabled",
                                json,
                                cn.edu.whu.zhizhida.ai.AiGuardFixtures.guard(),
                                BusinessMetrics.disabled());
        }

        @AfterEach
        void stop() {
                release.countDown();
                server.stop(0);
                handlers.shutdownNow();
        }

        ToolCallingChatClient.Request input(Duration timeout) {
                return new ToolCallingChatClient.Request(
                                client.configuration(),
                                List.of(json.createObjectNode().put("role", "user").put("content", "synthetic")),
                                List.of(),
                                true,
                                timeout,
                                "attempt-1");
        }

        @Test
        void completeJsonResponsePreservesContentAndRequestId() {
                var response = client.complete(input(Duration.ofSeconds(2)));
                assertThat(response.message().path("content").asString()).isEqualTo("中文");
                assertThat(response.finishReason()).isEqualTo("stop");
                assertThat(response.usage().status()).isEqualTo(ModelUsage.Status.MISSING);
                assertThat(attempt).isEqualTo("attempt-1");
                assertThat(sends.get()).isEqualTo(1);
        }

        @Test
        void attemptHeaderIsValidatedBeforeNetwork() {
                var value = input(Duration.ofSeconds(1));
                assertThatThrownBy(
                                () -> client.complete(
                                                new ToolCallingChatClient.Request(
                                                                value.config(),
                                                                value.messages(),
                                                                value.tools(),
                                                                true,
                                                                value.timeout(),
                                                                "invalid\r\nheader")))
                                .hasMessage("MODEL_ATTEMPT_INVALID");
                assertThat(sends.get()).isZero();
        }

        @ParameterizedTest
        @ValueSource(ints = { 307, 308, 401, 403, 429, 500, 502, 503 })
        void jsonErrorsAndRedirectsAreOnePhysicalSend(int code) {
                status = code;
                type = "application/json";
                payload = "private error";
                assertThatThrownBy(() -> client.complete(input(Duration.ofSeconds(2))))
                                .hasMessage("MODEL_HTTP_" + code);
                assertThat(sends.get()).isEqualTo(1);
        }

        @ParameterizedTest
        @ValueSource(strings = { "\"10\"", "10.5", "-1", "9223372036854775808" })
        void jsonUsageCannotBeCoercedBySdk(String tokens) {
                type = "application/json";
                payload = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"{}\",\"vendor\":{\"opaque\":true}}}],"
                                + "\"usage\":{\"prompt_tokens\":"
                                + tokens
                                + ",\"completion_tokens\":5,\"total_tokens\":15}}";
                var output = client.complete(input(Duration.ofSeconds(2)));
                assertThat(output.usage().status()).isEqualTo(ModelUsage.Status.INVALID);
                assertThat(output.message().at("/vendor/opaque").asBoolean()).isTrue();
        }

        @Test
        void rawHistoryMultipleResultsAndSchemaSurviveSdkProjection() {
                type = "application/json";
                payload = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"{}\"}}]}";
                var messages = json.readTree(
                                """
                                                [{"role":"user","content":"fixture"},{"role":"assistant","content":null,"reasoning_content":"private",
                                                 "vendor":{"id":"opaque"},"tool_calls":[
                                                 {"id":"a","type":"function","function":{"name":"read","arguments":"{}"}},
                                                 {"id":"b","type":"function","function":{"name":"read","arguments":"{}"}}]},
                                                 {"role":"tool","tool_call_id":"a","content":"one"},{"role":"tool","tool_call_id":"b","content":"two"}]
                                                """);
                var tool = json.readTree(
                                """
                                                {"type":"function","function":{"name":"read","parameters":{"type":"object",
                                                 "properties":{"id":{"type":"string"}},"required":["id"],"additionalProperties":false}}}
                                                """);
                var history = new java.util.ArrayList<tools.jackson.databind.JsonNode>();
                messages.forEach(history::add);
                var output = client.complete(
                                new ToolCallingChatClient.Request(
                                                client.configuration(),
                                                history,
                                                List.of(tool),
                                                true,
                                                Duration.ofSeconds(2),
                                                "raw-history"));
                var sent = json.readTree(request);
                assertThat(sent.path("messages")).isEqualTo(messages);
                assertThat(sent.path("tools").get(0)).isEqualTo(tool);
                assertThat(sent.path("tool_choice").asString()).isEqualTo("auto");
                assertThat(sent.at("/thinking/type").asString()).isEqualTo("disabled");
                assertThat(sent.has("temperature")).isFalse();
                assertThat(output.usage().status()).isEqualTo(ModelUsage.Status.MISSING);
        }

        @Test
        void executionProfileMatchesLoadedSdkJars() throws Exception {
                String modelVersion = jarVersion(
                                dev.langchain4j.model.openai.OpenAiChatModel.class, "langchain4j-open-ai");
                String codecVersion = jarVersion(
                                dev.langchain4j.jackson3.Jackson3JsonCodecFactory.class,
                                "langchain4j-core-jackson3");
                assertThat(client.executionProfile())
                                .containsEntry(
                                                "modelSdk",
                                                "langchain4j-" + modelVersion + "-jackson3-" + codecVersion);
        }

        private static String jarVersion(Class<?> type, String artifact)
                        throws java.net.URISyntaxException {
                String name = java.nio.file.Path.of(
                                type.getProtectionDomain().getCodeSource().getLocation().toURI())
                                .getFileName()
                                .toString();
                assertThat(name).startsWith(artifact + "-").endsWith(".jar");
                return name.substring(artifact.length() + 1, name.length() - ".jar".length());
        }

        @Test
        void jsonDeadlineIncludesStalledBodyAndInterruptKeepsItsFlag() throws Exception {
                type = "application/json";
                payload = "{";
                stall = true;
                long start = System.nanoTime();
                assertThatThrownBy(() -> client.complete(input(Duration.ofMillis(250))))
                                .hasMessage("MODEL_NETWORK");
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
                var error = new java.util.concurrent.CompletableFuture<Boolean>();
                var thread = Thread.ofVirtual()
                                .start(
                                                () -> {
                                                        try {
                                                                client.complete(input(Duration.ofSeconds(2)));
                                                                error.complete(false);
                                                        } catch (RuntimeException ex) {
                                                                error.complete(
                                                                                ex.getMessage().equals(
                                                                                                "MODEL_INTERRUPTED")
                                                                                                && Thread.currentThread()
                                                                                                                .isInterrupted());
                                                        }
                                                });
                org.awaitility.Awaitility.await()
                                .atMost(Duration.ofSeconds(1))
                                .until(() -> sends.get() == 2);
                thread.interrupt();
                assertThat(error.get(1, TimeUnit.SECONDS)).isTrue();
                stall = false;
                release.countDown();
                payload = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"{}\"}}]}";
                assertThat(client.complete(input(Duration.ofSeconds(2))).finishReason()).isEqualTo("stop");
        }

        @Test
        void malformedUtf8AndLargeBodiesAreRejectedBeforeSdkDecoding() {
                var subscriber = new DeepSeekJsonSubscriber(200);
                var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
                subscriber.onSubscribe(
                                new java.util.concurrent.Flow.Subscription() {
                                        public void request(long n) {
                                        }

                                        public void cancel() {
                                                cancelled.set(true);
                                        }
                                });
                subscriber.onNext(
                                List.of(java.nio.ByteBuffer.wrap(new byte[DeepSeekJsonSubscriber.MAX_BYTES + 1])));
                assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().join())
                                .cause()
                                .hasMessage("MODEL_RESPONSE_LIMIT");
                assertThat(cancelled).isTrue();
                var invalid = new DeepSeekJsonSubscriber(200);
                invalid.onSubscribe(
                                new java.util.concurrent.Flow.Subscription() {
                                        public void request(long n) {
                                        }

                                        public void cancel() {
                                        }
                                });
                invalid.onNext(List.of(java.nio.ByteBuffer.wrap(new byte[] { (byte) 0xc3, 0x28 })));
                invalid.onComplete();
                assertThatThrownBy(() -> invalid.getBody().toCompletableFuture().join())
                                .cause()
                                .hasMessage("MODEL_PROTOCOL");
        }

        @Test
        void deadlineClosesTheActualSocketDuringContinuousBody() throws Exception {
                var disconnected = new java.util.concurrent.CountDownLatch(1);
                server.createContext(
                                "/drip/chat/completions",
                                exchange -> {
                                        exchange.getRequestBody().readAllBytes();
                                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                                        exchange.sendResponseHeaders(200, 0);
                                        byte[] bytes = " ".repeat(16384)
                                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                        try {
                                                for (int i = 0; i < 100; i++) {
                                                        exchange.getResponseBody().write(bytes);
                                                        exchange.getResponseBody().flush();
                                                        Thread.sleep(20);
                                                }
                                        } catch (java.io.IOException ex) {
                                                disconnected.countDown();
                                        } catch (InterruptedException ex) {
                                                Thread.currentThread().interrupt();
                                        } finally {
                                                exchange.close();
                                        }
                                });
                var adapter = createClient("http://127.0.0.1:" + server.getAddress().getPort() + "/drip");
                var request = new ToolCallingChatClient.Request(
                                adapter.configuration(),
                                input(Duration.ofSeconds(2)).messages(),
                                List.of(),
                                false,
                                Duration.ofMillis(250),
                                "deadline");
                assertThatThrownBy(() -> adapter.complete(request)).hasMessage("MODEL_NETWORK");
                assertThat(disconnected.await(3, TimeUnit.SECONDS)).isTrue();
        }

        @Test
        void oversizedWireBodyCancelsBeforeItCanBecomeAModelResult() {
                server.createContext(
                                "/large/chat/completions",
                                exchange -> {
                                        exchange.getRequestBody().readAllBytes();
                                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                                        exchange.sendResponseHeaders(200, 0);
                                        try {
                                                exchange.getResponseBody()
                                                                .write(
                                                                                "x"
                                                                                                .repeat(1_048_577)
                                                                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                        } catch (java.io.IOException expectedCancellation) {
                                        } finally {
                                                exchange.close();
                                        }
                                });
                var adapter = createClient("http://127.0.0.1:" + server.getAddress().getPort() + "/large");
                var request = new ToolCallingChatClient.Request(
                                adapter.configuration(),
                                input(Duration.ofSeconds(2)).messages(),
                                List.of(),
                                false,
                                Duration.ofSeconds(2),
                                "large");
                assertThatThrownBy(() -> adapter.complete(request)).hasMessage("MODEL_RESPONSE_LIMIT");
        }
}
