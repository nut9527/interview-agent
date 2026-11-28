package cn.edu.whu.zhizhida.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.sun.net.httpserver.HttpServer;

import cn.edu.whu.zhizhida.ai.api.ModelUsage;
import cn.edu.whu.zhizhida.ai.api.ToolCallingChatClient;
import cn.edu.whu.zhizhida.common.UpstreamServiceException;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import tools.jackson.databind.json.JsonMapper;

class DeepSeekToolChatClientTest {
        final DeepSeekToolChatClient client = new DeepSeekToolChatClient(
                        "unused",
                        "http://localhost",
                        "fixture",
                        "enabled",
                        new JsonMapper(),
                        cn.edu.whu.zhizhida.ai.AiGuardFixtures.guard(),
                        BusinessMetrics.disabled());

        @ParameterizedTest
        @ValueSource(strings = { "", "not-json", "null", "[]", "{} {}" })
        void malformedProviderBodiesHaveAStableProtocolFailure(String body) {
                assertThatThrownBy(() -> client.parse(body))
                                .isInstanceOf(UpstreamServiceException.class)
                                .hasMessage("MODEL_PROTOCOL");
        }

        @ParameterizedTest
        @ValueSource(strings = { "{}", "42", "\"calls\"" })
        void finalOutputCannotHideMalformedToolCalls(String calls) {
                String body = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                                + "\"content\":\"{}\",\"tool_calls\":"
                                + calls
                                + "}}]}";
                assertThatThrownBy(() -> client.parse(body))
                                .isInstanceOf(UpstreamServiceException.class)
                                .hasMessage("MODEL_PROTOCOL");
        }

        @Test
        void emptyContentToolCallAndPrivateContinuationStateAreSupported() {
                var r = client.parse(
                                """
                                                {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"reasoning_content":"private continuation", "tool_calls":[{"id":"call1","type":"function","function":{"name":"search_knowledge","arguments":"{}"}}]}}]}
                                                """);
                assertThat(r.finishReason()).isEqualTo("tool_calls");
                assertThat(r.message().path("reasoning_content").asString())
                                .isEqualTo("private continuation");
                assertThat(r.usage().status()).isEqualTo(ModelUsage.Status.MISSING);
        }

        @ParameterizedTest
        @CsvSource({
                        "length, MODEL_FINISH_LENGTH",
                        "content_filter, MODEL_FINISH_CONTENT_FILTER",
                        "unknown, MODEL_PROTOCOL",
                        "tool_calls, MODEL_PROTOCOL",
                        "sensitive_vendor_detail, MODEL_PROTOCOL"
        })
        void abnormalFinishesHaveStableCodesAcceptedByTheWorker(String reason, String expectedCode) {
                String body = "{\"choices\":[{\"finish_reason\":\""
                                + reason
                                + "\",\"message\":{\"role\":\"assistant\",\"content\":\"{}\"}}]}";
                assertThatThrownBy(() -> client.parse(body))
                                .isInstanceOf(UpstreamServiceException.class)
                                .hasMessage(expectedCode)
                                .hasMessageMatching("MODEL_[A-Z0-9_]{1,48}");
        }

        @Test
        void reportedAndInvalidUsageStayDistinct() {
                assertThat(
                                client.parse(
                                                "{\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":5},\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"{}\"}}]}")
                                                .usage()
                                                .totalTokens())
                                .isEqualTo(5);
                assertThat(
                                client.parse(
                                                "{\"usage\":{},\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"{}\"}}]}")
                                                .usage()
                                                .status())
                                .isEqualTo(ModelUsage.Status.INVALID);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                        "{}", "{\"role\":\"user\",\"content\":\"{}\"}",
                        "{\"role\":\"assistant\",\"content\":42}",
                        "{\"role\":\"assistant\",\"content\":\" \"}",
                        "{\"role\":\"assistant\",\"content\":\"{}\",\"tool_calls\":[{}]}"
        })
        void malformedAssistantMessagesNeverBecomeBusinessResults(String message) {
                assertThatThrownBy(
                                () -> client.parse(
                                                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":"
                                                                + message
                                                                + "}]}"))
                                .isInstanceOf(IllegalStateException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                        "{\"id\":\"\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}",
                        "{\"id\":42,\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}",
                        "{\"id\":\"c\",\"type\":\"function\",\"function\":{\"name\":42,\"arguments\":\"{}\"}}",
                        "{\"id\":\"c\",\"type\":\"unknown\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}",
                        "{\"id\":\"c\",\"type\":\"function\",\"function\":{\"name\":\"\",\"arguments\":\"{}\"}}",
                        "{\"id\":\"c\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":{}}}"
        })
        void incompleteToolAssociationsAreRejected(String call) {
                assertThatThrownBy(
                                () -> client.parse(
                                                "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"tool_calls\":["
                                                                + call
                                                                + "]}}]}"))
                                .hasMessage("MODEL_PROTOCOL");
        }

        @Test
        void duplicateCallIdsCannotOverwriteProtocolAssociations() {
                String call = "{\"id\":\"c\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}";
                assertThatThrownBy(
                                () -> client.parse(
                                                "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"tool_calls\":["
                                                                + call
                                                                + ","
                                                                + call
                                                                + "]}}]}"))
                                .hasMessage("MODEL_PROTOCOL");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                        "null",
                        "{\"prompt_tokens\":1.5}",
                        "{\"prompt_tokens\":92233720368547758080}",
                        "{\"prompt_tokens\":-1,\"completion_tokens\":2,\"total_tokens\":1}",
                        "{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":6}"
        })
        void missingFractionalOverflowAndInconsistentUsageAreNeverZero(String usage) {
                var r = client.parse(
                                "{\"usage\":"
                                                + usage
                                                + ",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"{}\"}}]}");
                assertThat(r.usage().status()).isNotEqualTo(ModelUsage.Status.REPORTED);
                assertThat(r.usage().totalTokens()).isNull();
        }

        @Test
        void completeForwardsAllProtocolMessagesAndClosesToolsDuringRepair() throws Exception {
                var json = new JsonMapper();
                var captured = new AtomicReference<String>();
                var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext(
                                "/chat/completions",
                                exchange -> {
                                        captured.set(
                                                        new String(
                                                                        exchange.getRequestBody().readAllBytes(),
                                                                        java.nio.charset.StandardCharsets.UTF_8));
                                        byte[] reply = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"{}\"}}]}"
                                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                        exchange.sendResponseHeaders(200, reply.length);
                                        exchange.getResponseBody().write(reply);
                                        exchange.close();
                                });
                server.start();
                try {
                        var adapter = new DeepSeekToolChatClient(
                                        "synthetic-key",
                                        "http://127.0.0.1:" + server.getAddress().getPort() + "/",
                                        "fixture",
                                        "",
                                        json,
                                        cn.edu.whu.zhizhida.ai.AiGuardFixtures.guard(),
                                        BusinessMetrics.disabled());
                        var messages = List.of(
                                        json.readTree(
                                                        """
                                                                        {"role":"assistant","reasoning_content":"private continuation","tool_calls":[
                                                                        {"id":"c1","type":"function","function":{"name":"read","arguments":"{}"}}]}
                                                                        """),
                                        json.createObjectNode()
                                                        .put("role", "tool")
                                                        .put("tool_call_id", "c1")
                                                        .put("content", "evidence"));
                        var definitions = List.of(
                                        json.readTree(
                                                        """
                                                                        {"type":"function","function":{"name":"read","parameters":{"type":"object","properties":{}}}}
                                                                        """));
                        adapter.complete(
                                        new ToolCallingChatClient.Request(
                                                        adapter.configuration(),
                                                        messages,
                                                        definitions,
                                                        true,
                                                        Duration.ofSeconds(2),
                                                        "first"));
                        var body = json.readTree(captured.get());
                        assertThat(body.path("tool_choice").asString()).isEqualTo("auto");
                        assertThat(body.path("messages").toString())
                                        .contains("private continuation", "tool_call_id");
                        assertThat(Integer.parseInt(adapter.executionProfile().get("maxOutputTokens")))
                                        .isEqualTo(body.path("max_tokens").asInt());
                        assertThat(body.has("temperature")).isFalse();
                        assertThat(adapter.executionProfile().get("temperature")).isEqualTo("PROVIDER_DEFAULT");
                        assertThat(adapter.executionProfile().get("endpointSha256")).matches("[a-f0-9]{64}");
                        assertThat(adapter.executionProfile().toString())
                                        .doesNotContain("synthetic-key", "127.0.0.1");
                        adapter.complete(
                                        new ToolCallingChatClient.Request(
                                                        adapter.configuration(),
                                                        messages,
                                                        definitions,
                                                        false,
                                                        Duration.ofSeconds(2),
                                                        "repair"));
                        body = json.readTree(captured.get());
                        assertThat(body.path("tool_choice").asString()).isEqualTo("none");
                        assertThat(body.path("response_format").path("type").asString())
                                        .isEqualTo("json_object");
                        adapter.complete(
                                        new ToolCallingChatClient.Request(
                                                        adapter.configuration(),
                                                        messages,
                                                        List.of(),
                                                        false,
                                                        Duration.ofSeconds(2),
                                                        "legacy"));
                        assertThat(json.readTree(captured.get()).has("tools")).isFalse();
                } finally {
                        server.stop(0);
                }
        }

        @Test
        void credentialsConfigurationAndTimeoutLimitsRejectBeforeNetwork() {
                var noKey = new DeepSeekToolChatClient(
                                "",
                                "http://localhost",
                                "fixture",
                                null,
                                new JsonMapper(),
                                cn.edu.whu.zhizhida.ai.AiGuardFixtures.guard(),
                                BusinessMetrics.disabled());
                assertThatThrownBy(
                                () -> noKey.complete(
                                                new ToolCallingChatClient.Request(
                                                                noKey.configuration(),
                                                                List.of(),
                                                                List.of(),
                                                                true,
                                                                Duration.ofSeconds(1),
                                                                "no-key")))
                                .hasMessage("MODEL_CREDENTIALS_MISSING");
                for (var duration : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(26)))
                        assertThatThrownBy(
                                        () -> client.complete(
                                                        new ToolCallingChatClient.Request(
                                                                        client.configuration(),
                                                                        List.of(),
                                                                        List.of(),
                                                                        true,
                                                                        duration,
                                                                        "timeout")))
                                        .hasMessage("MODEL_CONFIG_CHANGED");
                assertThatThrownBy(
                                () -> client.complete(
                                                new ToolCallingChatClient.Request(
                                                                new ToolCallingChatClient.Config(
                                                                                "different", "enabled", "v"),
                                                                List.of(),
                                                                List.of(),
                                                                true,
                                                                Duration.ofSeconds(1),
                                                                "changed")))
                                .hasMessage("MODEL_CONFIG_CHANGED");
                var large = new JsonMapper()
                                .createObjectNode()
                                .put("role", "user")
                                .put("content", "x".repeat(160001));
                assertThatThrownBy(
                                () -> client.complete(
                                                new ToolCallingChatClient.Request(
                                                                client.configuration(),
                                                                List.of(large),
                                                                List.of(),
                                                                true,
                                                                Duration.ofSeconds(1),
                                                                "large")))
                                .hasMessage("MODEL_INPUT_BUDGET");
        }
}
