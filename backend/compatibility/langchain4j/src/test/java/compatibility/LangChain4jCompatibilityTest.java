package compatibility;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static compatibility.ModelHttpFixture.*;
import static org.assertj.core.api.Assertions.*;

@Timeout(10)
class LangChain4jCompatibilityTest {
    final JsonMapper json = new JsonMapper();
    ModelHttpFixture http;

    @BeforeEach
    void start() throws Exception {
        http = new ModelHttpFixture();
    }

    @AfterEach
    void stop() {
        http.close();
    }

    JdkHttpClientBuilder transport() {
        return JdkHttpClient.builder().httpClientBuilder(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER));
    }

    OpenAiChatModel model(String attempt) {
        return OpenAiChatModel.builder().baseUrl(http.baseUrl()).apiKey("synthetic-only")
                .modelName("deepseek-chat").maxTokens(2000).maxRetries(0).timeout(Duration.ofSeconds(2))
                .httpClientBuilder(transport()).returnThinking(true).sendThinking(true)
                .customHeaders(Map.of("X-Client-Request-Id", attempt))
                .customParameters(Map.of("thinking", Map.of("type", "disabled"))).build();
    }

    OpenAiStreamingChatModel streamingModel(String attempt) {
        return OpenAiStreamingChatModel.builder().baseUrl(http.baseUrl()).apiKey("synthetic-only")
                .modelName("deepseek-chat").maxTokens(2000).timeout(Duration.ofSeconds(2))
                .httpClientBuilder(transport()).returnThinking(true).sendThinking(true)
                .customHeaders(Map.of("X-Client-Request-Id", attempt))
                .customParameters(Map.of("thinking", Map.of("type", "disabled"))).build();
    }

    ChatRequest input() {
        return ChatRequest.builder().messages(UserMessage.from("synthetic JSON")).build();
    }

    JsonNode sent(int index) {
        return json.readTree(http.requests.get(index).body());
    }

    JsonNode raw(ChatResponse response) {
        return json.readTree(((OpenAiChatResponseMetadata) response.metadata()).rawHttpResponse().body());
    }

    CompletableFuture<ChatResponse> stream(String attempt) {
        var result = new CompletableFuture<ChatResponse>();
        streamingModel(attempt).chat(input(), new StreamingChatResponseHandler() {
            public void onCompleteResponse(ChatResponse response) {
                result.complete(response);
            }

            public void onError(Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    @Test
    void jackson3OnlyAndExplicitSingleCall() throws Exception {
        assertThatThrownBy(() -> Class.forName("com.fasterxml.jackson.databind.ObjectMapper"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThat(model("attempt-1").chat(input()).aiMessage().text()).isEqualTo("{}");
        assertThat(http.requests).hasSize(1);
        assertThat(http.requests.getFirst().attempt()).isEqualTo("attempt-1");
        assertThat(sent(0).at("/thinking/type").asString()).isEqualTo("disabled");
        assertThat(sent(0).path("max_tokens").asInt()).isEqualTo(2000);
        assertThat(sent(0).has("temperature")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = { 429, 500, 502, 503, 307, 308 })
    void errorsAndRedirectsNeverRetry(int status) {
        http.status = status;
        assertThatThrownBy(() -> model("attempt-error").chat(input())).isInstanceOf(RuntimeException.class);
        assertThat(http.requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(ints = { 429, 500, 502, 503, 307, 308 })
    void streamingErrorsAndRedirectsNeverRetry(int status) {
        http.status = status;
        http.contentType = "text/event-stream";
        http.response = chunk("{\"role\":\"assistant\",\"content\":\"draft\"}", null);
        assertThatThrownBy(() -> stream("stream-error").get(4, TimeUnit.SECONDS))
                .isInstanceOf(java.util.concurrent.ExecutionException.class);
        assertThat(http.requests).hasSize(1);
    }

    @Test
    void toolsAndTwoResultsContinueOnlyWhenApplicationCallsAgainButTypedSchemaLosesRestriction() {
        var schema = JsonObjectSchema.builder().addStringProperty("query").required("query")
                .additionalProperties(false).build();
        var tool = ToolSpecification.builder().name("read_reference").description("synthetic read")
                .parameters(schema).build();
        var request = ChatRequest.builder().messages(UserMessage.from("synthetic JSON"))
                .toolSpecifications(tool).build();
        http.response = envelope(
                """
                        {"role":"assistant","content":null,"reasoning_content":"private",
                         "tool_calls":[
                          {"id":"call-1","type":"function","function":{"name":"read_reference","arguments":"{\"query\":\"cache\"}"}},
                          {"id":"call-2","type":"function","function":{"name":"read_reference","arguments":"{\"query\":\"lock\"}"}}]}
                        """
                        .replace("{\"query\":\"cache\"}", "{\\\"query\\\":\\\"cache\\\"}")
                        .replace("{\"query\":\"lock\"}", "{\\\"query\\\":\\\"lock\\\"}"),
                "tool_calls",
                "{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}");
        var first = model("attempt-1").chat(request);
        assertThat(http.requests).hasSize(1);
        assertThat(first.aiMessage().toolExecutionRequests()).extracting(r -> r.id()).containsExactly("call-1",
                "call-2");
        assertThat(first.aiMessage().thinking()).isEqualTo("private");
        assertThat(sent(0).at("/tools/0/function/parameters"))
                .isEqualTo(json.readTree(
                        "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}"));
        http.response = envelope("{\"role\":\"assistant\",\"content\":\"{}\"}", "stop", "null");
        model("attempt-2").chat(ChatRequest.builder().messages(UserMessage.from("synthetic JSON"), first.aiMessage(),
                ToolExecutionResultMessage.from("call-1", "read_reference", "source-1"),
                ToolExecutionResultMessage.from("call-2", "read_reference", "source-2")).build());
        assertThat(http.requests).extracting(Request::attempt).containsExactly("attempt-1", "attempt-2");
        assertThat(sent(1).at("/messages/1/reasoning_content").asString()).isEqualTo("private");
        assertThat(sent(1).at("/messages/2/tool_call_id").asString()).isEqualTo("call-1");
        assertThat(sent(1).at("/messages/3/tool_call_id").asString()).isEqualTo("call-2");
    }

    @Test
    void rawResponseRetainsUnknownFieldsWhileTypedContinuationLosesThem() {
        http.response = envelope("{\"role\":\"assistant\",\"content\":\"{}\",\"provider_extension\":{\"opaque\":true}}",
                "stop", "null");
        var first = model("attempt-1").chat(input());
        assertThat(raw(first).at("/choices/0/message/provider_extension/opaque").asBoolean()).isTrue();
        model("attempt-2")
                .chat(ChatRequest.builder().messages(UserMessage.from("synthetic"), first.aiMessage()).build());
        // 此处复现接入阻碍；测试通过只说明问题可复现，不代表类型映射无损。
        assertThat(sent(1).at("/messages/1").has("provider_extension")).isFalse();
    }

    @Test
    void rawUsagePreservesTheInvalidTypeThatTypedUsageCoerces() {
        http.response = envelope("{\"role\":\"assistant\",\"content\":\"{}\"}", "stop",
                "{\"prompt_tokens\":\"10\",\"completion_tokens\":5,\"total_tokens\":15}");
        var output = model("usage").chat(input());
        assertThat(output.tokenUsage().inputTokenCount()).isEqualTo(10);
        assertThat(raw(output).at("/usage/prompt_tokens").isString()).isTrue();
    }

    @Test
    void missingUsageMustBeReadFromRawResponse() {
        var output = model("missing-usage").chat(input());
        assertThat(raw(output).path("usage").isNull()).isTrue();
    }

    @Test
    void rawSseMetadataRetainsUnknownDeltasButOmitsDone() throws Exception {
        http.contentType = "text/event-stream";
        http.response = chunk("{\"role\":\"assistant\",\"content\":\"中文\",\"provider_extension\":\"opaque\"}", "stop")
                + "data: [DONE]\n\n";
        var output = stream("stream-1").get(4, TimeUnit.SECONDS);
        assertThat(output.aiMessage().text()).isEqualTo("中文");
        var raw = ((OpenAiChatResponseMetadata) output.metadata()).rawServerSentEvents();
        assertThat(raw).extracting(event -> event.data()).anyMatch(data -> data.contains("provider_extension"))
                .doesNotContain("[DONE]");
        assertThat(sent(0).at("/stream_options/include_usage").asBoolean()).isTrue();
        assertThat(http.requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = { "none", "finish-only", "done-only" })
    void truncatedStreamsAreAcceptedBySdkAndRequireStrictBoundary(String termination) throws Exception {
        http.contentType = "text/event-stream";
        http.response = chunk("{\"role\":\"assistant\",\"content\":\"draft\"}",
                termination.equals("finish-only") ? "stop" : null)
                + (termination.equals("done-only") ? "data: [DONE]\n\n" : "");
        var output = stream("incomplete").get(4, TimeUnit.SECONDS);
        assertThat(output.aiMessage().text()).isEqualTo("draft");
        assertThat(http.requests).hasSize(1);
    }
}
