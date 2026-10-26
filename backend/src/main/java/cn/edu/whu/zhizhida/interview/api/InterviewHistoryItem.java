package cn.edu.whu.zhizhida.interview.api;

import java.time.Instant;

public record InterviewHistoryItem(
        String sessionId,
        String targetRole,
        Long resumeId,
        InterviewMode mode,
        int totalRounds,
        InterviewState state,
        int turnCount,
        int answeredCount,
        String preview,
        Instant createdAt,
        Instant updatedAt
) {
    public InterviewHistoryItem(String sessionId, String targetRole, Long resumeId, InterviewState state,
                                int turnCount, int answeredCount, String preview, Instant createdAt, Instant updatedAt) {
        this(sessionId, targetRole, resumeId, InterviewMode.GENERAL, 3,
                state, turnCount, answeredCount, preview, createdAt, updatedAt);
    }
}
