package cn.edu.whu.zhizhida.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import cn.edu.whu.zhizhida.ai.api.ModelUsage;
import cn.edu.whu.zhizhida.ai.api.ToolCallingChatClient;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import tools.jackson.databind.json.JsonMapper;

/** 显式启用后才执行协议冒烟测试，不评价模型质量；最多发送 3 次、预留 4500 个输出 Token。 */
@EnabledIfEnvironmentVariable(named = "RUN_PROVIDER_ACCEPTANCE", matches = "true")
class ProviderAcceptanceTest {
        @Test
        void syntheticJsonAndToolContinuation() throws Exception {
                var json = new JsonMapper();
                var rows = new ArrayList<Map<String, Object>>();
                var reportDirectory = Path.of(System.getProperty("provider.report-directory", "target"));
                Files.createDirectories(reportDirectory);
                String key = System.getenv("AI_API_KEY");
                String url = System.getenv("ACCEPTANCE_BASE_URL");
                String model = System.getenv("ACCEPTANCE_MODEL");
                assertThat(key).isNotBlank();
                assertThat(System.getenv("ACCEPTANCE_MAX_REQUESTS")).isEqualTo("3");
                assertThat(System.getenv("ACCEPTANCE_MAX_OUTPUT_TOKENS")).isEqualTo("4500");
                try {
                        var row = row(rows, "json", 500);
                        long start = System.nanoTime();
                        var client = new DeepSeekChatClient(
                                        key,
                                        url,
                                        model,
                                        "disabled",
                                        25,
                                        json,
                                        cn.edu.whu.zhizhida.ai.AiGuardFixtures.guard(),
                                        BusinessMetrics.disabled());
                        var response = client.complete(
                                        "Return only a JSON object. Copy the supplied synthetic fact into the answer field.",
                                        "Synthetic fact: cache. Return {\"answer\":\"cache\"}.",
                                        0,
                                        500);
                        assertThat(response.payload().path("answer").asString()).isEqualTo("cache");
                        finish(row, response.usage(), start);
                        var tool = new DeepSeekToolChatClient(
                                        key,
                                        url,
                                        model,
                                        "disabled",
                                        json,
                                        cn.edu.whu.zhizhida.ai.AiGuardFixtures.guard(),
                                        BusinessMetrics.disabled());
                        var messages = new ArrayList<tools.jackson.databind.JsonNode>();
                        messages.add(
                                        json.createObjectNode()
                                                        .put("role", "system")
                                                        .put(
                                                                        "content",
                                                                        "Synthetic protocol acceptance. First call lookup_reference exactly once with query cache. After receiving its result return only JSON {\"answer\":\"synthetic-reference\"}."));
                        messages.add(
                                        json.createObjectNode()
                                                        .put("role", "user")
                                                        .put("content", "Look up the synthetic cache reference."));
                        var definition = json.readTree(
                                        "{\"type\":\"function\",\"function\":{\"name\":\"lookup_reference\",\"description\":\"Read synthetic reference\",\"parameters\":{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"],\"additionalProperties\":false}}}");
                        row = row(rows, "tool", 2000);
                        start = System.nanoTime();
                        var first = tool.complete(
                                        new ToolCallingChatClient.Request(
                                                        tool.configuration(),
                                                        messages,
                                                        List.of(definition),
                                                        true,
                                                        Duration.ofSeconds(25),
                                                        "acceptance-" + UUID.randomUUID()));
                        assertThat(first.finishReason()).isEqualTo("tool_calls");
                        assertThat(first.message().path("tool_calls").size()).isEqualTo(1);
                        var call = first.message().path("tool_calls").path(0);
                        assertThat(call.at("/function/name").asString()).isEqualTo("lookup_reference");
                        finish(row, first.usage(), start);
                        messages.add(first.message());
                        messages.add(
                                        json.createObjectNode()
                                                        .put("role", "tool")
                                                        .put("tool_call_id", call.path("id").asString())
                                                        .put("content", "synthetic-reference"));
                        row = row(rows, "continuation", 2000);
                        start = System.nanoTime();
                        var last = tool.complete(
                                        new ToolCallingChatClient.Request(
                                                        tool.configuration(),
                                                        messages,
                                                        List.of(definition),
                                                        false,
                                                        Duration.ofSeconds(25),
                                                        "acceptance-" + UUID.randomUUID()));
                        assertThat(last.finishReason()).isEqualTo("stop");
                        assertThat(
                                        json.readTree(last.message().path("content").asString())
                                                        .path("answer")
                                                        .asString())
                                        .isEqualTo("synthetic-reference");
                        finish(row, last.usage(), start);
                } finally {
                        Files.writeString(
                                        reportDirectory.resolve("provider-acceptance.json"),
                                        json.writerWithDefaultPrettyPrinter()
                                                        .writeValueAsString(
                                                                        Map.of(
                                                                                        "recordedAt",
                                                                                        java.time.Instant.now()
                                                                                                        .toString(),
                                                                                        "scope",
                                                                                        "synthetic-protocol-smoke-not-quality",
                                                                                        "model",
                                                                                        model,
                                                                                        "sdkVersion",
                                                                                        LangChain4jVersions.OPEN_AI,
                                                                                        "maxRequests",
                                                                                        3,
                                                                                        "reservedOutputTokens",
                                                                                        4500,
                                                                                        "attempts",
                                                                                        rows)));
                }
        }

        private Map<String, Object> row(List<Map<String, Object>> rows, String phase, int maximum) {
                var row = new LinkedHashMap<String, Object>();
                row.put("phase", phase);
                row.put("maxOutputTokens", maximum);
                row.put("status", "FAILED_OR_UNKNOWN");
                rows.add(row);
                return row;
        }

        private void finish(Map<String, Object> row, ModelUsage usage, long start) {
                row.put("status", "PASSED");
                row.put("usage", usage);
                row.put("elapsedMillis", (System.nanoTime() - start) / 1_000_000);
        }
}
