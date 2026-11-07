package cn.edu.whu.zhizhida.learning.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import cn.edu.whu.zhizhida.learning.api.GoalTraining.Item;

public record LearningGoal(String id, String title, String competencyId, String completionCriterion,
        int requiredScenarios, List<String> questionIds, String status, long revision,
        int maxRuns, int maxTraining, int maxModelAttempts, int maxTools,
        int reservedRuns, int reservedTraining, int reservedModelAttempts, int reservedTools,
        Instant expiresAt, List<Plan> plans, long criteriaRevision, List<String> dimensions) {
    public record Create(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}") String requestId,
            @NotBlank @Size(max=160) String title,
            @NotNull @Size(min=2,max=3) List<@NotBlank @Size(max=64) String> questionIds,
            @Min(2) @Max(3) int requiredScenarios, @Min(2) @Max(30) int maxRuns,
            @Min(2) @Max(6) int maxTraining, @Min(4) @Max(120) int maxModelAttempts,
            @Min(3) @Max(90) int maxTools) { }
    public enum Action { PAUSE, RESUME, CANCEL }
    public record Change(@Min(1) long expectedRevision, @NotNull Action action) { }
    public record Command(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}") String requestId,
            @Min(1) long expectedRevision, @Size(max=20) List<@NotBlank @Size(max=64) String> observationIds) {
        public Command { observationIds=observationIds==null?List.of():List.copyOf(observationIds); }
    }
    public record Edit(@Min(1) int expectedRevision,
            @NotNull @Valid @Size(min=1,max=3) List<@NotNull Item> items) { }
    public record Confirm(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}") String requestId,
            @Min(1) int expectedRevision) { }
    public record Revision(@Min(1) int expectedRevision) { }
    public record Criteria(@Min(1) long expectedRevision, @Min(2) @Max(3) int requiredScenarios,
            @NotNull @Size(min=2,max=6) List<@NotBlank String> dimensions,
            @NotNull @AssertTrue Boolean confirmed) { }
    public record Complete(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}") String requestId,
            @Min(1) long expectedRevision, @NotBlank @Pattern(regexp="[0-9a-f]{64}") String evidenceHash,
            @NotBlank @Size(max=1000) String reviewNote, @NotNull @AssertTrue Boolean confirmed) { }
    public record Plan(String id, String goalId, int revision, String status, String contentHash,
            List<GoalTraining.Item> items, String limitation, Instant expiresAt, List<String> taskIds,
            List<PracticeMemoryAccess.Reference> observationRefs) { }
    public record Review(String outcome, int validScenarios, int requiredScenarios,
            List<GoalTraining.Result> results, String limitation, String evidenceHash,
            long goalRevision, long criteriaRevision, List<String> dimensions) { }
}
