package cn.edu.whu.zhizhida.learning.api;

import cn.edu.whu.zhizhida.evaluation.api.AssessmentResult;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

/** 对外学习记录只包含定性观察提案，不包含模型供应商的私有消息。 */
public record LearningMemory(Preferences preferences, Profile profile, Sync sync, List<Observation> observations,
        long total, int page, int size) {
    public record Preferences(boolean personalizationEnabled, long revision) { }
    public record Sync(long pending, long failed) { }
    public record PreferenceUpdate(@NotNull Boolean personalizationEnabled, @Min(0) long expectedRevision) { }
    public record Correction(@Min(1) long expectedRevision, @NotBlank @Size(max=1000) String text) { }
    public record Removal(@Min(1) long expectedRevision) { }
    public record Fact(int round, String competencyId, String dimension, String rubricVersion,
            String executionMode, AssessmentResult.Observation observation, AssessmentResult.Claim claim,
            List<AssessmentResult.Evidence> evidence) {
        public Fact { evidence = List.copyOf(evidence); }
    }
    public record Observation(String id, String sourceSessionId, String sourceVersion, int sourceRound,
            String competencyId, String dimension, String rubricVersion, String executionMode,
            String status, long revision, Fact fact, String correction, Instant createdAt) { }
    public record Group(String competencyId, String dimension, String rubricVersion, String executionMode,
            int samples, int observed, int unknown, int disputed, String latestObservationId) { }
    public record Profile(String reducerVersion, long revision, List<Group> groups) { }
    public record Export(String formatVersion, Instant exportedAt, Preferences preferences,
            List<Observation> observations, String nextCursor, boolean hasMore) { }
}
