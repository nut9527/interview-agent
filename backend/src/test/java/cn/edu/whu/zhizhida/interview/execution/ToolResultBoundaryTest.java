package cn.edu.whu.zhizhida.interview.execution;

import java.util.List;
import java.util.Set;
import cn.edu.whu.zhizhida.common.execution.ToolRegistry;
import cn.edu.whu.zhizhida.interview.api.KnowledgeCatalog;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolResultBoundaryTest {
    private final JsonMapper json = new JsonMapper();

    @Test void exactSerializedBudgetPreservesEscapedQuotesAndRejectsWholeOversizedResult() {
        var payload = json.createObjectNode().put("status", "FOUND").put("content", "\"\\\n😀");
        int exact = payload.toString().length();
        var definition = json.readTree("""
            {"type":"function","function":{"name":"fixture","description":"test","parameters":{}}}
            """);
        InterviewToolProvider provider = () -> List.of(new ToolRegistry.Registration<>(definition, "v1", Set.of(),
                new ToolRegistry.Limits(1000, exact), (c, a) -> {},
                (c, a) -> new InterviewEvidenceTools.Result(payload, List.of())));
        var tools = new InterviewEvidenceTools(json, mock(KnowledgeCatalog.class), List.of(provider));
        var returned = tools.checkedPayload("fixture", payload);
        assertThat(returned).isEqualTo(payload).isNotSameAs(payload);
        payload.put("extra", "x");
        assertThatThrownBy(() -> tools.checkedPayload("fixture", payload)).hasMessage("TOOL_RESULT_BUDGET");
        assertThat(returned.has("extra")).isFalse();
        assertThatThrownBy(() -> tools.checkedPayload("fixture", null)).hasMessage("TOOL_RESULT_INVALID");
        assertThatThrownBy(() -> tools.checkedPayload("fixture", json.createArrayNode())).hasMessage("TOOL_RESULT_INVALID");
    }

    @Test void limitsAreFrozenInManifestAndInvalidValuesFailAtRegistration() {
        var definition = json.readTree("""
            {"type":"function","function":{"name":"fixture","description":"test","parameters":{}}}
            """);
        var first = new ToolRegistry.Registration<String, String>(definition, "v1", Set.of(),
                new ToolRegistry.Limits(100, 500), (c, a) -> {}, (c, a) -> c);
        var changed = new ToolRegistry.Registration<String, String>(definition, "v1", Set.of(),
                new ToolRegistry.Limits(101, 500), (c, a) -> {}, (c, a) -> c);
        assertThat(new ToolRegistry<>(List.of(first)).manifest()).isNotEqualTo(new ToolRegistry<>(List.of(changed)).manifest());
        assertThat(new ToolRegistry<>(List.of(first)).manifest().getFirst())
                .containsEntry("maxResultChars", 500).containsEntry("resultMeasurement", "SERIALIZED_UTF16");
        for (long time : new long[] {0, -1, 30_001})
            assertThatThrownBy(() -> new ToolRegistry.Limits(time, 1)).isInstanceOf(IllegalArgumentException.class);
        for (int chars : new int[] {0, -1, 64_001})
            assertThatThrownBy(() -> new ToolRegistry.Limits(1, chars)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void toolsCannotSpendFinalOrCommitTime() {
        var budget = BudgetProfile.production("B0");
        assertThat(budget.toolAvailableMillis("ACTION", 55_001)).isEqualTo(1);
        assertThat(budget.toolAvailableMillis("FINAL", 55_001)).isEqualTo(1);
        assertThatThrownBy(() -> budget.toolAvailableMillis("ACTION", 55_000)).hasMessage("RUN_TOOL_BUDGET");
        assertThatThrownBy(() -> budget.toolAvailableMillis("FINAL", 55_000)).hasMessage("RUN_TOOL_BUDGET");
        assertThatThrownBy(() -> budget.toolAvailableMillis("ACTION", Long.MIN_VALUE)).hasMessage("RUN_TOOL_BUDGET");
        assertThatThrownBy(() -> budget.toolAvailableMillis("invented", 90_000)).hasMessage("RUN_CHECKPOINT_INVALID");
        assertThat(budget.toolAvailableMillis("LEGACY", 5_001)).isEqualTo(1);
        assertThatThrownBy(() -> budget.toolAvailableMillis("LEGACY", 5_000)).hasMessage("RUN_TOOL_BUDGET");
    }

    @Test void runTimeBoundaryStopsToolWithDistinctReasonAndNoReturnedResult() {
        var definition = json.readTree("""
            {"type":"function","function":{"name":"fixture","description":"test","parameters":{}}}
            """);
        InterviewToolProvider provider = () -> List.of(new ToolRegistry.Registration<>(definition, "v1", Set.of(),
                new ToolRegistry.Limits(1000, 256), (c, a) -> {}, (c, a) -> {
                    try { new java.util.concurrent.CountDownLatch(1).await(); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                    return new InterviewEvidenceTools.Result(json.createObjectNode().put("status", "EMPTY"), List.of());
                }));
        try (var guard = new cn.edu.whu.zhizhida.common.execution.ToolExecutionGuard(1)) {
            var tools = new InterviewEvidenceTools(json, mock(KnowledgeCatalog.class), List.of(provider), guard);
            assertThatThrownBy(() -> tools.execute(mock(cn.edu.whu.zhizhida.interview.api.InterviewSession.class),
                    List.of(), "fixture", json.createObjectNode(), List.of(),
                    cn.edu.whu.zhizhida.learning.api.PracticeMemoryAccess.Selection.none(), List.of(), 30))
                    .hasMessage("RUN_TOOL_BUDGET");
        }
    }
}
