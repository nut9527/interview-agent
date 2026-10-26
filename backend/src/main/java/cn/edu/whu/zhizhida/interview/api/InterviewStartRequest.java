package cn.edu.whu.zhizhida.interview.api;

import jakarta.validation.constraints.*;

public record InterviewStartRequest(
        @NotBlank @Size(max = 160) String targetRole,
        @Size(max = 4000) String resumeSummary,
        @Positive Long resumeId,
        @Size(max = 20000) String jobDescription,
        InterviewMode mode,
        @Min(1) @Max(12) Integer totalRounds,
        @Size(min = 1, max = 80) String targetJobId,
        @PositiveOrZero Long targetJobRevision,
        InterviewDifficulty difficulty
) {
    public InterviewStartRequest(String role, String summary, Long resumeId) {
        this(role, summary, resumeId, null, null, null, null, null, null);
    }
}
