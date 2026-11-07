package compatibility;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import static compatibility.ModelHttpFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Timeout(10)
class LangChain4jLifecycleTest {
    ModelHttpFixture http;
    @BeforeEach void start() throws Exception { http = new ModelHttpFixture(); }
    @AfterEach void stop() { http.close(); }

    OpenAiStreamingChatModel model(Duration timeout) {
        return OpenAiStreamingChatModel.builder().baseUrl(http.baseUrl()).apiKey("synthetic-only")
                .modelName("deepseek-chat").timeout(timeout)
                .httpClientBuilder(JdkHttpClient.builder().httpClientBuilder(HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER))).build();
    }

    ChatRequest input() { return ChatRequest.builder().messages(UserMessage.from("synthetic")).build(); }
    StreamingChatResponseHandler completion(CompletableFuture<ChatResponse> result) {
        return new StreamingChatResponseHandler() {
            public void onCompleteResponse(ChatResponse response) { result.complete(response); }
            public void onError(Throwable error) { result.completeExceptionally(error); }
        };
    }

    @Test void utf8AndSseCanBeSplitAtEveryByte() throws Exception {
        http.responder = exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            var bytes = (chunk("{\"role\":\"assistant\",\"content\":\"中文😀\"}", "stop")
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            for (byte value : bytes) { exchange.getResponseBody().write(value); exchange.getResponseBody().flush(); }
        };
        var result = new CompletableFuture<ChatResponse>();
        model(Duration.ofSeconds(2)).chat(input(), completion(result));
        assertThat(result.get(4, TimeUnit.SECONDS).aiMessage().text()).isEqualTo("中文😀");
        assertThat(http.requests).hasSize(1);
    }

    @Test void cancellationClosesTheActualBodyAndDoesNotCompleteOrRetry() throws Exception {
        var cancelled = new CountDownLatch(1);
        var completed = new AtomicBoolean();
        http.responder = exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            byte[] bytes = chunk("{\"role\":\"assistant\",\"content\":\"draft\"}", null).getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < 100; i++) {
                exchange.getResponseBody().write(bytes);
                exchange.getResponseBody().flush();
                pause(20);
            }
        };
        model(Duration.ofSeconds(2)).chat(input(), new StreamingChatResponseHandler() {
            public void onPartialResponse(PartialResponse partial, PartialResponseContext context) {
                context.streamingHandle().cancel();
                cancelled.countDown();
            }
            public void onCompleteResponse(ChatResponse response) { completed.set(true); }
            public void onError(Throwable error) { }
        });
        assertThat(cancelled.await(3, TimeUnit.SECONDS)).isTrue();
        await().atMost(Duration.ofSeconds(3)).until(() -> http.disconnected.get() == 1);
        assertThat(completed).isFalse();
        assertThat(http.requests).hasSize(1);
    }

    @Test void sdkStreamingTimeoutDoesNotCoverStalledBodyAfterHeaders() throws Exception {
        http.responder = exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(chunk("{\"role\":\"assistant\",\"content\":\"draft\"}", null).getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            pause(1500);
            exchange.getResponseBody().write((chunk("{}", "stop") + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
        };
        var result = new CompletableFuture<ChatResponse>();
        model(Duration.ofMillis(300)).chat(input(), completion(result));
        assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(result.get(3, TimeUnit.SECONDS).aiMessage().text()).isEqualTo("draft");
        assertThat(http.requests).hasSize(1);
    }

    @Test void sdkSynchronousTimeoutDoesNotCoverStalledBodyAfterHeaders() {
        http.responder = exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            pause(1500);
        };
        var model = OpenAiChatModel.builder().baseUrl(http.baseUrl()).apiKey("synthetic-only").modelName("deepseek-chat")
                .maxRetries(0).timeout(Duration.ofMillis(300)).httpClientBuilder(JdkHttpClient.builder()
                        .httpClientBuilder(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                                .followRedirects(HttpClient.Redirect.NEVER))).build();
        var started = System.nanoTime();
        assertThatThrownBy(() -> model.chat("synthetic")).isInstanceOf(RuntimeException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThan(Duration.ofSeconds(1));
        assertThat(http.requests).hasSize(1);
    }

    static void pause(long millis) throws java.io.IOException {
        try { Thread.sleep(millis); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new java.io.IOException("fixture interrupted", ex); }
    }
}
