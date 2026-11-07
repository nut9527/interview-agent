package cn.edu.whu.zhizhida.interview.execution;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class RunContextAssemblerTest {
    private final JsonMapper json = new JsonMapper();
    private final RunContextAssembler assembler = new RunContextAssembler(json);

    List<JsonNode> messages() {
        return new ArrayList<>(List.of(json.createObjectNode().put("role", "system").put("content", "权限/目标"),
                json.createObjectNode().put("role", "user").put("content", "当前完整回答：😀\n不得改变我的要求")));
    }

    void batch(List<JsonNode> messages, String id, String args, String content) {
        var assistant = json.createObjectNode().put("role", "assistant").putNull("content")
                .put("reasoning_content", "opaque-private").put("provider_signature", "signature");
        assistant.putArray("tool_calls").addObject().put("id", id).put("type", "function")
                .putObject("function").put("name", "read_material_section").put("arguments", args);
        messages.add(assistant);
        messages.add(json.createObjectNode().put("role", "tool").put("tool_call_id", id).put("content", content));
    }

    String result(String text) {
        return json.createObjectNode().put("status", "FOUND").put("sourceVersion", "v3")
                .put("sha256", "original-source-hash").put("content", text).toString();
    }

    @Test void newestResultAndPrivateFieldsRemainExactWhileDuplicateOldBodyBecomesAddressable() {
        var messages = messages();
        String raw = result("引文😀\n".repeat(400));
        batch(messages, "first", "{\"sectionId\":\"resume-0\",\"maxChars\":2000}", raw);
        batch(messages, "second", "{\"sectionId\":\"resume-0\",\"maxChars\":2000}", raw);
        String before = json.writeValueAsString(messages);
        var prepared = assembler.assemble("config", messages, List.of(), true);
        assertThat(prepared.omittedResults()).isEqualTo(1);
        assertThat(prepared.messages().get(5)).isEqualTo(messages.get(5));
        assertThat(prepared.messages().get(2)).isEqualTo(messages.get(2));
        assertThat(prepared.messages().get(4)).isEqualTo(messages.get(4));
        assertThat(prepared.messages().get(1)).isEqualTo(messages.get(1));
        var pointer = json.readTree(prepared.messages().get(3).path("content").asText());
        assertThat(pointer.path("status").asText()).isEqualTo("CONTEXT_OMITTED");
        assertThat(pointer.path("sourceVersion").asText()).isEqualTo("v3");
        assertThat(pointer.path("receiptSha256").asText()).isEqualTo(InterviewRunStore.sha(raw));
        assertThat(pointer.path("reread")).isEqualTo(messages.get(2).at("/tool_calls/0/function"));
        assertThat(json.writeValueAsString(messages)).isEqualTo(before);
        assertThat(assembler.assemble("config", json.readValue(before, new tools.jackson.core.type.TypeReference<List<JsonNode>>() {}),
                List.of(), true)).isEqualTo(prepared);
    }

    @Test void windowEvictsOldestWholeResultsAndKeepsSourceLocatorsAndExactQuotes() {
        var messages = messages();
        var root = json.createObjectNode().put("status", "FOUND");
        var ref = root.putArray("references").addObject().put("id", "doc").put("version", "v7").put("sha256", "hash");
        ref.putArray("excerpts").addObject().put("chunkId", "chunk-3").put("startLine", 8)
                .put("endLine", 15).put("content", "未解决矛盾😀".repeat(300));
        batch(messages, "a", "one", root.toString());
        String recent = result("原文\r\n😀".repeat(300));
        batch(messages, "b", "two", recent);
        var prepared = assembler.assemble("config", messages, List.of(), false, 20_000, 3000);
        assertThat(prepared.omittedResults()).isEqualTo(1);
        assertThat(prepared.toolChars()).isLessThanOrEqualTo(3000);
        assertThat(prepared.messages().get(5).path("content").asText()).isEqualTo(recent);
        var locator = json.readTree(prepared.messages().get(3).path("content").asText()).at("/sourceLocators/0");
        assertThat(locator.path("id").asText()).isEqualTo("doc");
        assertThat(locator.path("version").asText()).isEqualTo("v7");
        assertThat(locator.path("chunkIds").get(0).asText()).isEqualTo("chunk-3");
        assertThat(locator.has("content")).isFalse();
    }

    @Test void fullInputBoundaryIncludesSchemaEscapingAndRepairAndCanEvictOlderBody() {
        var messages = messages();
        batch(messages, "old", "one", result("x".repeat(3000)));
        batch(messages, "new", "two", result("y".repeat(1000)));
        messages.add(json.createObjectNode().put("role", "assistant").put("content", "无效候选😀"));
        messages.add(json.createObjectNode().put("role", "user").put("content", "FINAL 修正要求"));
        var prepared = assembler.assemble("config", messages, List.of(), false, 3500, 24_000);
        assertThat(prepared.omittedResults()).isEqualTo(1);
        assertThat(prepared.messages().get(6)).isEqualTo(messages.get(6));
        assertThat(prepared.messages().get(7)).isEqualTo(messages.get(7));
        var hugeSchema = json.createObjectNode().put("description", "\"\n".repeat(1000));
        assertThatThrownBy(() -> assembler.assemble("config", messages, List.of(hugeSchema), false, 3500, 24_000))
                .hasMessage("RUN_CONTEXT_BUDGET");
    }

    @Test void freshParallelBatchIsNeverPartiallyHiddenOrTruncated() {
        var messages = messages();
        batch(messages, "one", "one", result("a".repeat(1000)));
        var assistant = (ObjectNode) messages.get(2);
        assistant.withArray("tool_calls").addObject().put("id", "two").put("type", "function")
                .putObject("function").put("name", "read_material_section").put("arguments", "two");
        messages.add(json.createObjectNode().put("role", "tool").put("tool_call_id", "two").put("content", result("b".repeat(1000))));
        assertThatThrownBy(() -> assembler.assemble("config", messages, List.of(), true, 20_000, 1500))
                .hasMessage("RUN_CONTEXT_BUDGET");
        assertThat(assembler.assemble("config", messages, List.of(), true).messages()).containsExactlyElementsOf(messages);
    }

    @Test void oneCharacterBoundaryIncludesTheEscapedProjectionNoticeBeforeChoosingMoreEvictions() {
        var messages = messages();
        batch(messages, "a", "same", result("a".repeat(2000)));
        batch(messages, "b", "other", result("b".repeat(2000)));
        batch(messages, "c", "same", result("a".repeat(2000)));
        var first = assembler.assemble("config", messages, List.of(), true);
        assertThat(first.omittedResults()).isEqualTo(1);
        var smaller = assembler.assemble("config", messages, List.of(), true, first.inputChars() - 1, 24_000);
        assertThat(smaller.omittedResults()).isEqualTo(2);
        assertThat(smaller.inputChars()).isLessThan(first.inputChars());
        assertThat(assembler.assemble("config", messages, List.of(), true, smaller.inputChars(), 24_000)).isEqualTo(smaller);
    }

    @Test void sameBodyFromDifferentSourcesIsNotDeduplicatedAndTinyResultsNeverGrow() {
        var messages = messages();
        batch(messages, "a", "section-a", result("x".repeat(1000)));
        batch(messages, "b", "section-b", result("x".repeat(1000)));
        assertThat(assembler.assemble("config", messages, List.of(), true).omittedResults()).isZero();
        batch(messages, "c", "small", "{}");
        batch(messages, "d", "small", "{}");
        assertThat(assembler.assemble("config", messages, List.of(), true).omittedResults()).isZero();
    }

    @Test void rereadRestoresFullResultInLatestBatchAndOpaqueResultsAreSafe() {
        var messages = messages();
        batch(messages, "a", "one", "not-json".repeat(200));
        batch(messages, "b", "two", result("y".repeat(1000)));
        batch(messages, "c", "one", "not-json".repeat(200));
        var prepared = assembler.assemble("config", messages, List.of(), true);
        assertThat(prepared.omittedResults()).isEqualTo(1);
        assertThat(prepared.messages().getLast()).isEqualTo(messages.getLast());
    }

    @Test void noToolInputIsNotSilentlyCutAndHashesBindFullRequestNotOnlyMessages() {
        var messages = messages();
        var first = assembler.assemble("config", messages, List.of(), false);
        assertThat(first.messages()).containsExactlyElementsOf(messages);
        assertThat(first.toolChars()).isZero();
        assertThat(first.requestHash()).isNotEqualTo(assembler.assemble("changed", messages, List.of(), false).requestHash());
        assertThat(first.requestHash()).isNotEqualTo(assembler.assemble("config", messages, List.of(), true).requestHash());
        assertThat(first.requestHash()).isNotEqualTo(assembler.assemble("config", messages,
                List.of(json.createObjectNode().put("name", "new-tool")), false).requestHash());
        assertThatThrownBy(() -> assembler.assemble("config", messages, List.of(), false, 50, 24_000))
                .hasMessage("RUN_CONTEXT_BUDGET");
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "orphan", "wrong-id", "duplicate-id", "interleaved", "bad-arguments"})
    void malformedToolPairingCannotReachTheProvider(String fault) {
        var messages = messages();
        batch(messages, "a", "{}", "{}");
        switch (fault) {
            case "missing" -> messages.removeLast();
            case "orphan" -> messages.remove(2);
            case "wrong-id" -> ((ObjectNode) messages.getLast()).put("tool_call_id", "other");
            case "duplicate-id" -> batch(messages, "a", "{}", "{}");
            case "interleaved" -> messages.add(3, json.createObjectNode().put("role", "user").put("content", "invalid"));
            case "bad-arguments" -> ((ObjectNode) messages.get(2).at("/tool_calls/0/function")).put("arguments", 123);
        }
        assertThatThrownBy(() -> assembler.assemble("config", messages, List.of(), true)).hasMessage("RUN_CONTEXT_PROTOCOL");
    }
}
