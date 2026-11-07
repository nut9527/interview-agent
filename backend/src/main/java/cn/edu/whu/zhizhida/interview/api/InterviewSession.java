package cn.edu.whu.zhizhida.interview.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public record InterviewSession(
        String sessionId,
        String targetRole,
        Long resumeId,
        // 持久化的材料文本快照；Agent 状态单独存于 agent，调用 AI 时才组合成编码上下文。
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
        InterviewDifficulty difficulty,
        InterviewExperience experience,
        Boolean sourceRevoked
) {
    public InterviewSession {
        difficulty = difficulty == null ? InterviewDifficulty.STANDARD : difficulty;
        sourceRevoked = Boolean.TRUE.equals(sourceRevoked);
    }
    public InterviewSession(String sessionId, String targetRole, Long resumeId, String resumeSnapshot,
            Long resumeRevision, Long jobId, String jobSnapshot, InterviewMode mode, int totalRounds,
            Instant answerLeaseUntil, InterviewState state, int turnCount, List<InterviewTurn> turns,
            InterviewPreparation preparation, InterviewAgentState agent, InterviewDifficulty difficulty, InterviewExperience experience) {
        this(sessionId,targetRole,resumeId,resumeSnapshot,resumeRevision,jobId,jobSnapshot,mode,totalRounds,answerLeaseUntil,state,turnCount,turns,preparation,agent,difficulty,experience,null);
    }
    public InterviewSession withSourceRevoked(Boolean value) {
        return new InterviewSession(sessionId,targetRole,resumeId,resumeSnapshot,resumeRevision,jobId,jobSnapshot,mode,totalRounds,answerLeaseUntil,state,turnCount,turns,preparation,agent,difficulty,experience,value);
    }

    public InterviewSession(String sessionId, String targetRole, Long resumeId, String resumeSnapshot,
            Long resumeRevision, Long jobId, String jobSnapshot, InterviewMode mode, int totalRounds,
            Instant answerLeaseUntil, InterviewState state, int turnCount, List<InterviewTurn> turns,
            InterviewPreparation preparation, InterviewAgentState agent, InterviewDifficulty difficulty) {
        this(sessionId, targetRole, resumeId, resumeSnapshot, resumeRevision, jobId, jobSnapshot, mode,
                totalRounds, answerLeaseUntil, state, turnCount, turns, preparation, agent, difficulty, null);
    }

    public InterviewSession withExperience(InterviewExperience value) {
        return new InterviewSession(sessionId, targetRole, resumeId, resumeSnapshot, resumeRevision, jobId,
                jobSnapshot, mode, totalRounds, answerLeaseUntil, state, turnCount, turns, preparation, agent, difficulty, value, sourceRevoked);
    }

    /** 正式面试进行期间不展示反馈、参考答案或追问意图。 */
    public InterviewSession publicView() {
        if (experience == null || experience.format() != InterviewExperience.Format.FORMAL || state == InterviewState.FINISHED) return this;
        return new InterviewSession(sessionId, targetRole, resumeId, resumeSnapshot, resumeRevision, jobId,
                jobSnapshot, mode, totalRounds, answerLeaseUntil, state, turnCount,
                turns.stream().filter(t -> !"system".equals(t.speaker()) || !t.content().startsWith("即时反馈：")).toList(),
                null, null, difficulty, experience, sourceRevoked);
    }

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
                jobSnapshot, mode, totalRounds, answerLeaseUntil, state, turnCount, turns, preparation, checkpoint, difficulty, experience, sourceRevoked);
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

    /** 对话中的角色与正文；可选请求标识用于关联提交，不作为授权凭据。 */
    @Schema(name = "cn.edu.whu.zhizhida.interview.api.InterviewTurn")
    public record InterviewTurn(
            String speaker,
            String content,
            @JsonInclude(JsonInclude.Include.NON_NULL) String requestId
    ) {
        public InterviewTurn(String speaker, String content) {
            this(speaker, content, null);
        }
    }
}
