package cn.edu.whu.zhizhida.interview.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import cn.edu.whu.zhizhida.interview.api.KnowledgeSnapshot;

class InterviewRunCheckpointTest {
    private final JsonMapper json = new JsonMapper();

    @Test void initialRetrievalRequiresSelectedContentAndNoUsableExcerpts() {
        var empty = new KnowledgeSnapshot("a", "A", "fixture", 1, "hash", " ", List.of());
        var missing = new KnowledgeSnapshot("b", "B", "fixture", 1, "hash", "正文", List.of());
        var excerpt = new KnowledgeSnapshot.Excerpt("c:1", "", 1, 1, "原文");
        var present = new KnowledgeSnapshot("c", "C", "fixture", 1, "hash", "原文", List.of(excerpt));
        assertThat(InterviewRunWorker.needsKnowledgePrefetch(List.of())).isFalse();
        assertThat(InterviewRunWorker.needsKnowledgePrefetch(List.of(empty))).isFalse();
        assertThat(InterviewRunWorker.needsKnowledgePrefetch(List.of(missing, present))).isFalse();
        assertThat(InterviewRunWorker.needsKnowledgePrefetch(List.of(empty, missing))).isTrue();
    }

    @Test void reservedPrefetchCanResumeOnlyOnTheInitialToolRoute() {
        var prefetch = base("KNOWLEDGE_PREFETCH").put("fixedReserved", true);
        assertThat(InterviewRunCheckpoint.read(json, prefetch.toString(), "TOOL")).isEqualTo(prefetch);
        assertThatThrownBy(() -> InterviewRunCheckpoint.read(json, prefetch.toString(), "FIXED")).hasMessage("RUN_CHECKPOINT_INVALID");
        assertThatThrownBy(() -> InterviewRunCheckpoint.read(json, base("FIXED").toString(), "TOOL")).hasMessage("RUN_CHECKPOINT_INVALID");
        prefetch.put("outputPhase", "FINAL").put("actionRepaired", false);
        assertThatThrownBy(() -> InterviewRunCheckpoint.read(json, prefetch.toString(), "TOOL")).hasMessage("RUN_CHECKPOINT_INVALID");
    }

    @Test void phasedCheckpointRejectsMissingOrCrossedPhaseWithoutChangingLegacyReads() {
        var cp = base("ACTION_VALIDATE").put("outputPhase", "ACTION").put("actionRepaired", false);
        var response = json.createObjectNode().put("role", "assistant").put("content", "{}");
        cp.set("response", response);
        cp.withArray("messages").add(response);
        assertThat(InterviewRunCheckpoint.read(json, cp.toString(), "TOOL")).isEqualTo(cp);
        for (String phase : new String[] {"FINAL", "unknown", ""}) {
            cp.put("outputPhase", phase);
            assertThatThrownBy(() -> InterviewRunCheckpoint.read(json, cp.toString(), "TOOL")).hasMessage("RUN_CHECKPOINT_INVALID");
        }
        cp.remove("outputPhase");
        assertThatThrownBy(() -> InterviewRunCheckpoint.read(json, cp.toString(), "TOOL")).hasMessage("RUN_CHECKPOINT_INVALID");
        cp.put("stage", "VALIDATE");
        assertThat(InterviewRunCheckpoint.read(json, cp.toString(), "TOOL")).isEqualTo(cp);
        cp.put("outputPhase", "ACTION");
        assertThatThrownBy(() -> InterviewRunCheckpoint.read(json, cp.toString(), "TOOL")).hasMessage("RUN_CHECKPOINT_INVALID");
        var tool = tool(0).put("outputPhase", "FINAL").put("actionRepaired", false);
        assertThatThrownBy(() -> InterviewRunCheckpoint.read(json, tool.toString(), "TOOL")).hasMessage("RUN_CHECKPOINT_INVALID");
        tool.put("outputPhase", "ACTION").put("actionRepaired", true);
        assertThatThrownBy(() -> InterviewRunCheckpoint.read(json, tool.toString(), "TOOL")).hasMessage("RUN_CHECKPOINT_INVALID");
    }

    @Test
    void nullMeansNewButPersistedEmptyOrMalformedDataCannotRestartTheRun() {
        assertThat(InterviewRunCheckpoint.read(json, null, "TOOL").isEmpty()).isTrue();
        assertThatThrownBy(() -> InterviewRunCheckpoint.read(json, "{}", "TOOL"))
                .isInstanceOfSatisfying(InterviewRunCheckpoint.Invalid.class, ex -> {
                    assertThat(ex.reason()).isEqualTo(InterviewRunCheckpoint.Reason.MISSING_FIELD);
                    assertThat(ex.path()).isEqualTo("stage");
                });
        for (String value : new String[] { "[1]", "null", "\"PRIVATE_SOURCE\"", "{private", "{} {}" }) {
            assertThatThrownBy(() -> InterviewRunCheckpoint.read(json, value, "TOOL"))
                    .isInstanceOf(InterviewRunCheckpoint.Invalid.class).hasMessage("RUN_CHECKPOINT_INVALID")
                    .hasNoCause();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "MODEL", "FIXED", "TOOL", "VALIDATE" })
    void legalUnversionedStagesRoundTripWithoutResettingCountersOrReceipts(String stage) {
        var cp = stage.equals("TOOL") ? tool(1) : base(stage);
        if (stage.equals("VALIDATE")) {
            var response = json.createObjectNode().put("role", "assistant").put("content", "{}");
            cp.set("response", response);
            cp.withArray("messages").add(response);
        }
        String saved = cp.toString();
        assertThat(InterviewRunCheckpoint.read(json, saved, stage.equals("FIXED") ? "FIXED" : "TOOL"))
                .isEqualTo(cp);
        assertThat(cp.has("version")).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidToolStates")
    void inconsistentToolProgressIsRejectedBeforeItCanSkipOrReplayWork(String path, Consumer<ObjectNode> corrupt) {
        var cp = tool(1);
        corrupt.accept(cp);
        assertThatThrownBy(() -> InterviewRunCheckpoint.read(json, cp.toString(), "TOOL"))
                .isInstanceOfSatisfying(InterviewRunCheckpoint.Invalid.class, ex -> {
                    assertThat(ex.path()).isEqualTo(path);
                    assertThat(ex.getMessage()).isEqualTo("RUN_CHECKPOINT_INVALID");
                    assertThat(ex.getCause()).isNull();
                });
    }

    static Stream<Arguments> invalidToolStates() {
        return Stream.of(
                invalid("stage", cp -> cp.put("stage", "PRIVATE_STAGE")),
                invalid("stage", cp -> cp.put("repaired", true)),
                invalid("repaired", cp -> cp.put("repaired", "false")),
                invalid("sources", cp -> cp.put("sources", "PRIVATE_SOURCE")),
                invalid("toolIndex", cp -> cp.put("toolIndex", -1)),
                invalid("toolIndex", cp -> cp.put("toolIndex", 2)),
                invalid("toolIndex", cp -> cp.put("toolIndex", 0.5)),
                invalid("toolIndex", cp -> cp.put("toolIndex", 4294967296L)),
                invalid("batchReserved", cp -> cp.put("batchReserved", false)),
                invalid("batchReserved", cp -> cp.remove("batchReserved")),
                invalid("messages", cp -> cp.withArray("messages").remove(2)),
                invalid("messages.tool_call_id", cp -> ((ObjectNode) cp.path("messages").get(2))
                        .put("tool_call_id", "other")),
                invalid("loop", cp -> cp.put("loop", "private")),
                invalid("loop.steps", cp -> cp.withObject("loop").put("steps", -1)),
                invalid("loop.evidence", cp -> cp.withObject("loop").put("evidence", "private")),
                invalid("loop.closed", cp -> cp.withObject("loop").put("closed", true)),
                invalid("loop.stopReason", cp -> cp.withObject("loop").put("stopReason", "private")));
    }

    private static Arguments invalid(String path, Consumer<ObjectNode> corrupt) { return Arguments.of(path, corrupt); }

    @Test
    void finalStageRequiresTheSameAssistantOutputAndToolStageRequiresToolRoute() {
        var cp = base("VALIDATE");
        cp.set("response", json.createObjectNode().put("role", "assistant").put("content", "{}"));
        assertThatThrownBy(() -> InterviewRunCheckpoint.read(json, cp.toString(), "TOOL"))
                .isInstanceOfSatisfying(InterviewRunCheckpoint.Invalid.class,
                        ex -> assertThat(ex.path()).isEqualTo("messages"));
        assertThatThrownBy(() -> InterviewRunCheckpoint.read(json, tool(0).toString(), "FIXED"))
                .isInstanceOfSatisfying(InterviewRunCheckpoint.Invalid.class,
                        ex -> assertThat(ex.path()).isEqualTo("stage"));
    }

    private ObjectNode base(String stage) {
        var cp = json.createObjectNode().put("stage", stage).put("repaired", false);
        cp.putArray("sources");
        cp.putArray("messages").addObject().put("role", "user").put("content", "PRIVATE_INPUT");
        return cp;
    }

    private ObjectNode tool(int index) {
        var cp = base("TOOL").put("toolIndex", index).put("batchReserved", index > 0);
        var response = json.createObjectNode().put("role", "assistant").putNull("content");
        response.putArray("tool_calls").addObject().put("id", "saved").put("type", "function")
                .putObject("function").put("name", "read_material_section")
                .put("arguments", "{\"sectionId\":\"resume-0\",\"maxChars\":2000}");
        cp.set("response", response);
        cp.withArray("messages").add(response);
        if (index > 0) cp.withArray("messages").addObject().put("role", "tool")
                .put("tool_call_id", "saved").put("content", "{\"status\":\"FOUND\"}");
        return cp;
    }
}
