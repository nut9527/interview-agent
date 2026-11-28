package cn.edu.whu.zhizhida.interview.execution;

import static org.assertj.core.api.Assertions.assertThat;

import cn.edu.whu.zhizhida.common.execution.ToolRegistry;
import cn.edu.whu.zhizhida.evaluation.api.AssessmentResult;
import cn.edu.whu.zhizhida.learning.api.LearningMemory;
import cn.edu.whu.zhizhida.learning.api.PracticeMemoryAccess;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PracticeEvidenceToolProviderTest {
    final JsonMapper json = new JsonMapper();

    @Test
    void progressComparesDistinctSessionsAndDoesNotAssumeRecallOrderIsChronological() {
        var older = observation("older", "session-1", 1, Instant.parse("2026-01-01T00:00:00Z"));
        var latest = observation("latest", "session-2", 3, Instant.parse("2026-02-01T00:00:00Z"));
        var earlierRound = observation("round-2", "session-2", 2, latest.createdAt());
        var selection =
                new PracticeMemoryAccess.Selection(
                        PracticeMemoryAccess.VERSION,
                        "real",
                        Instant.EPOCH,
                        List.of("storage"),
                        List.of(
                                new PracticeMemoryAccess.Reference(
                                        latest.id(),
                                        1,
                                        latest.sourceSessionId(),
                                        latest.sourceVersion())));
        var context =
                new InterviewToolProvider.Context(
                        List.of(),
                        List.of(),
                        List.of(),
                        selection,
                        List.of(older, earlierRound, latest));
        var registry = new ToolRegistry<>(new PracticeEvidenceToolProvider(json).tools());
        var args = json.createObjectNode().put("competencyId", "storage").put("limit", 5);
        var result =
                registry.execute(
                                "get_learning_progress",
                                args,
                                Set.of(InterviewToolProvider.MEMORY_SCOPE),
                                context)
                        .payload();
        var group = result.path("items").get(0);
        assertThat(group.path("latestObservationId").asString()).isEqualTo("latest");
        assertThat(group.path("latest").path("sourceRound").asInt()).isEqualTo(3);
        assertThat(group.path("previous").path("observationId").asString()).isEqualTo("older");
        assertThat(result.toString()).doesNotContain("不要传回的正文");
        assertThat(result.path("truncated").asBoolean()).isFalse();
    }

    private LearningMemory.Observation observation(
            String id, String session, int round, Instant at) {
        var claim =
                new AssessmentResult.Claim(
                        "claim",
                        "不要传回的正文",
                        List.of(),
                        AssessmentResult.ClaimStatus.INSUFFICIENT,
                        "",
                        "待复核");
        var fact =
                new LearningMemory.Fact(
                        round,
                        "storage",
                        "RESPONSIBILITY",
                        "qualitative-v1",
                        "real",
                        AssessmentResult.Observation.NOT_OBSERVED,
                        claim,
                        List.of());
        return new LearningMemory.Observation(
                id,
                session,
                "v1",
                round,
                "storage",
                "RESPONSIBILITY",
                "qualitative-v1",
                "real",
                "ACTIVE",
                1,
                fact,
                null,
                at);
    }
}
