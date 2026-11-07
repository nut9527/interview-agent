package compatibility;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static compatibility.ModelHttpFixture.*;
import static compatibility.LangChain4jLifecycleTest.pause;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Timeout(10)
class LangChain4jDeadlineTest {
    ModelHttpFixture http;
    DeadlineProbe deadline;
    @BeforeEach void start() throws Exception {
        http = new ModelHttpFixture();
        deadline = new DeadlineProbe(JdkHttpClient.builder().httpClientBuilder(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)));
    }
    @AfterEach void stop() { deadline.close(); http.close(); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void wholeBodyDeadlineCancelsTheSocketForJsonAndStreaming(boolean streaming) throws Exception {
        http.responder = exchange -> {
            exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, 0);
            var bytes = (streaming ? chunk("{\"role\":\"assistant\",\"content\":\"draft\"}", null) : " ".repeat(4096))
                    .getBytes(StandardCharsets.UTF_8);
            // 持续在空闲超时前发送数据，验证只有总截止时间才能终止该响应。
            for (int i = 0; i < 100; i++) {
                exchange.getResponseBody().write(bytes);
                exchange.getResponseBody().flush();
                pause(20);
            }
        };
        var result = new CompletableFuture<ChatResponse>();
        if (streaming) {
            OpenAiStreamingChatModel.builder().baseUrl(http.baseUrl()).apiKey("synthetic-only").modelName("deepseek-chat")
                    .timeout(Duration.ofMillis(300)).httpClientBuilder(deadline).build()
                    .chat(ChatRequest.builder().messages(UserMessage.from("synthetic")).build(), new StreamingChatResponseHandler() {
                        public void onCompleteResponse(ChatResponse response) { result.complete(response); }
                        public void onError(Throwable error) { result.completeExceptionally(error); }
                    });
            assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasRootCauseMessage("MODEL_DEADLINE");
        } else {
            var model = OpenAiChatModel.builder().baseUrl(http.baseUrl()).apiKey("synthetic-only").modelName("deepseek-chat")
                    .maxRetries(0).timeout(Duration.ofMillis(300)).httpClientBuilder(deadline).build();
            assertThatThrownBy(() -> model.chat("synthetic")).isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("MODEL_DEADLINE");
        }
        await().atMost(Duration.ofSeconds(3)).until(() -> http.disconnected.get() == 1);
        assertThat(http.requests).hasSize(1);
    }

    @Test void interruptingSynchronousCallClosesTheSocket() throws Exception {
        http.responder = exchange -> {
            exchange.sendResponseHeaders(200, 0);
            for (int i = 0; i < 100; i++) {
                exchange.getResponseBody().write(" ".repeat(4096).getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                pause(20);
            }
        };
        var model = OpenAiChatModel.builder().baseUrl(http.baseUrl()).apiKey("synthetic-only").modelName("deepseek-chat")
                .maxRetries(0).timeout(Duration.ofSeconds(5)).httpClientBuilder(deadline).build();
        var stopped = new CompletableFuture<Boolean>();
        var caller = Thread.ofVirtual().start(() -> {
            try { model.chat("synthetic"); stopped.complete(false); }
            catch (RuntimeException error) { stopped.complete(Thread.currentThread().isInterrupted()); }
        });
        try {
            await().atMost(Duration.ofSeconds(2)).until(() -> http.requests.size() == 1);
            caller.interrupt();
            assertThat(stopped.get(2, TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(3)).until(() -> http.disconnected.get() == 1);
        } finally { caller.interrupt(); caller.join(2000); }
    }
}
