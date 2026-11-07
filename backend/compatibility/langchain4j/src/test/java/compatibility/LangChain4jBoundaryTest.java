package compatibility;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static compatibility.ModelHttpFixture.*;
import static org.assertj.core.api.Assertions.*;

@Timeout(10)
class LangChain4jBoundaryTest {
    final JsonMapper json = new JsonMapper();
    ModelHttpFixture http;
    BoundaryProbe probe;
    @BeforeEach void start() throws Exception {
        http = new ModelHttpFixture();
        probe = new BoundaryProbe(JdkHttpClient.builder().httpClientBuilder(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)), "reserved-1");
    }
    @AfterEach void stop() { http.close(); }

    OpenAiChatModel model(String attempt) {
        return OpenAiChatModel.builder().baseUrl(http.baseUrl()).apiKey("synthetic-only").modelName("deepseek-chat")
                .maxRetries(0).timeout(Duration.ofSeconds(2)).httpClientBuilder(probe)
                .customHeaders(Map.of("X-Client-Request-Id", attempt)).build();
    }

    CompletableFuture<ChatResponse> stream() {
        var result = new CompletableFuture<ChatResponse>();
        OpenAiStreamingChatModel.builder().baseUrl(http.baseUrl()).apiKey("synthetic-only").modelName("deepseek-chat")
                .timeout(Duration.ofSeconds(2)).httpClientBuilder(probe)
                .customHeaders(Map.of("X-Client-Request-Id", "reserved-1")).build()
                .chat(ChatRequest.builder().messages(UserMessage.from("synthetic")).build(), new StreamingChatResponseHandler() {
                    public void onCompleteResponse(ChatResponse response) { result.complete(response); }
                    public void onError(Throwable error) { result.completeExceptionally(error); }
                });
        return result;
    }

    @Test void noAdmissionMeansNoSendAndAnAttemptCannotSendTwice() {
        assertThatThrownBy(() -> model("unreserved").chat("synthetic")).isInstanceOf(RuntimeException.class);
        assertThat(http.requests).isEmpty();
        var model = model("reserved-1");
        model.chat("synthetic");
        assertThatThrownBy(() -> model.chat("synthetic")).isInstanceOf(RuntimeException.class);
        assertThat(http.requests).hasSize(1);
    }

    @Test void rawSnapshotPreservesUnknownHistoryReasoningToolIdsAndSchema() {
        probe.originalMessages = json.readTree("""
                [{"role":"user","content":"synthetic"},
                 {"role":"assistant","content":null,"reasoning_content":"private","provider_extension":{"id":"opaque"},
                  "tool_calls":[{"id":"call-1","type":"function","function":{"name":"read_reference","arguments":"{}"}}]},
                 {"role":"tool","tool_call_id":"call-1","content":"synthetic source"}]
                """);
        probe.originalTools = json.readTree("""
                [{"type":"function","function":{"name":"read_reference","parameters":
                 {"type":"object","properties":{},"required":[],"additionalProperties":false}}}]
                """);
        // 使用项目的 Jackson 3 表示进行持久化与重载，独立于 SDK 消息类型。
        var history = json.readTree(probe.originalMessages.toString());
        model("reserved-1").chat("synthetic");
        var sent = json.readTree(http.requests.getFirst().body());
        assertThat(sent.path("messages")).isEqualTo(history);
        assertThat(sent.path("tools")).isEqualTo(probe.originalTools);
    }

    @ParameterizedTest
    @CsvSource(value = {"10|5|15|REPORTED", "\"10\"|5|15|INVALID", "10.0|5|15|INVALID",
            "-1|5|4|INVALID", "10|5|16|INVALID", "null|5|15|INVALID", "9223372036854775808|5|15|INVALID"}, delimiter = '|')
    void rawUsageIsClassifiedBeforeSdkCoercion(String input, String output, String total, BoundaryProbe.Usage expected) {
        http.response = envelope("{\"role\":\"assistant\",\"content\":\"{}\"}", "stop",
                "{\"prompt_tokens\":" + input + ",\"completion_tokens\":" + output + ",\"total_tokens\":" + total + "}");
        try { model("reserved-1").chat("synthetic"); }
        catch (RuntimeException ex) {
            // 原始响应校验完成分类后，SDK 仍可能因数值越界而拒绝转换。
            assertThat(expected).isEqualTo(BoundaryProbe.Usage.INVALID);
        }
        assertThat(probe.usage).isEqualTo(expected);
        assertThat(http.requests).hasSize(1);
    }

    @Test void absentUsageStaysMissing() {
        model("reserved-1").chat("synthetic");
        assertThat(probe.usage).isEqualTo(BoundaryProbe.Usage.MISSING);
    }

    @ParameterizedTest @ValueSource(strings = {"none", "finish-only", "done-only", "length", "content_filter"})
    void rawBoundaryRejectsIncompleteOrUnsuccessfulStreams(String termination) {
        http.contentType = "text/event-stream";
        String finish = switch (termination) {
            case "finish-only" -> "stop";
            case "length", "content_filter" -> termination;
            default -> null;
        };
        http.response = chunk("{\"role\":\"assistant\",\"content\":\"draft\"}", finish)
                + (termination.equals("none") || termination.equals("finish-only") ? "" : "data: [DONE]\n\n");
        assertThatThrownBy(() -> stream().get(4, TimeUnit.SECONDS))
                .isInstanceOf(java.util.concurrent.ExecutionException.class).hasRootCauseMessage("MODEL_STREAM_INCOMPLETE");
        assertThat(http.requests).hasSize(1);
    }

    @Test void rawBoundarySeesDoneUnknownDeltasAndInvalidStreamUsage() throws Exception {
        http.contentType = "text/event-stream";
        http.response = chunk("{\"role\":\"assistant\",\"content\":\"中文\",\"provider_extension\":{\"opaque\":true}}", "stop")
                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":\"10\",\"completion_tokens\":5,\"total_tokens\":15}}\n\n"
                + "data: [DONE]\n\n";
        assertThat(stream().get(4, TimeUnit.SECONDS).aiMessage().text()).isEqualTo("中文");
        assertThat(probe.done).isTrue();
        assertThat(probe.usage).isEqualTo(BoundaryProbe.Usage.INVALID);
        assertThat(probe.deltas.getFirst().at("/provider_extension/opaque").asBoolean()).isTrue();
    }
}
