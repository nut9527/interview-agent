package cn.edu.whu.zhizhida.interview.api;

import java.time.Instant;
import java.util.List;

public record InterviewSession(
        String sessionId,
        String targetRole,
        Long resumeId,
        String resumeSnapshot,
        Long resumeRevision,
        Long jobId,
        String jobSnapshot,
        InterviewMode mode,
        int totalRounds,
        Instant answerLeaseUntil,
        InterviewState state,
        int turnCount,
        List<InterviewTurn> turns,
        InterviewPreparation preparation,
        InterviewAgentState agent,
        InterviewDifficulty difficulty
) {
    public InterviewSession { difficulty = difficulty == null ? InterviewDifficulty.STANDARD : difficulty; }

    public InterviewSession(String sessionId, String targetRole, Long resumeId, String resumeSnapshot,
            Long resumeRevision, Long jobId, String jobSnapshot, InterviewMode mode, int totalRounds,
            Instant answerLeaseUntil, InterviewState state, int turnCount, List<InterviewTurn> turns,
            InterviewPreparation preparation, InterviewAgentState agent) {
        this(sessionId, targetRole, resumeId, resumeSnapshot, resumeRevision, jobId, jobSnapshot, mode,
                totalRounds, answerLeaseUntil, state, turnCount, turns, preparation, agent, InterviewDifficulty.STANDARD);
    }

    public InterviewSession(String sessionId, String targetRole, Long resumeId, String resumeSnapshot,
            Long resumeRevision, Long jobId, String jobSnapshot, InterviewMode mode, int totalRounds,
            Instant answerLeaseUntil, InterviewState state, int turnCount, List<InterviewTurn> turns,
            InterviewPreparation preparation) {
        this(sessionId, targetRole, resumeId, resumeSnapshot, resumeRevision, jobId, jobSnapshot,
                mode, totalRounds, answerLeaseUntil, state, turnCount, turns, preparation, null);
    }

    public InterviewSession withAgent(InterviewAgentState checkpoint) {
        return new InterviewSession(sessionId, targetRole, resumeId, resumeSnapshot, resumeRevision, jobId,
                jobSnapshot, mode, totalRounds, answerLeaseUntil, state, turnCount, turns, preparation, checkpoint, difficulty);
    }
    public InterviewSession(String sessionId, String targetRole, Long resumeId, String resumeSnapshot,
            Long resumeRevision, Long jobId, String jobSnapshot, InterviewMode mode, int totalRounds,
            Instant answerLeaseUntil, InterviewState state, int turnCount, List<InterviewTurn> turns) {
        this(sessionId, targetRole, resumeId, resumeSnapshot, resumeRevision, jobId, jobSnapshot,
                mode, totalRounds, answerLeaseUntil, state, turnCount, turns, null);
    }
    public InterviewSession(String sessionId, String targetRole, Long resumeId, String resumeSnapshot,
                            InterviewState state, int turnCount, List<InterviewTurn> turns) {
        this(sessionId, targetRole, resumeId, resumeSnapshot, null, null, "",
                InterviewMode.GENERAL, 3, null, state, turnCount, turns);
    }
}
