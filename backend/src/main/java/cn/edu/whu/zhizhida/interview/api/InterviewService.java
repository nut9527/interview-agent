package cn.edu.whu.zhizhida.interview.api;

import cn.edu.whu.zhizhida.common.PageRequest;
import cn.edu.whu.zhizhida.common.PageResult;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.time.Instant;

/** 面向当前账号的面试用例入口；查询和删除均须校验归属；写入通过 InterviewRun 命令受理。 */
public interface InterviewService {
    PageResult<InterviewHistoryItem> listHistory(PageRequest page);

    InterviewSession findSession(String sessionId);

    PageResult<InterviewHistoryItem> listRetries(String sessionId, PageRequest page);

    void deleteSession(String sessionId);

    /** 创建面试的输入；可选简历、岗位与训练来源须由服务端确认归属并冻结版本。 */
    @Schema(name = "cn.edu.whu.zhizhida.interview.api.InterviewStartRequest")
    public record InterviewStartRequest(
            @NotBlank @Size(max = 160) String targetRole,
            @Size(max = 4000) String resumeSummary,
            @Positive Long resumeId,
            @Size(max = 20000) String jobDescription,
            InterviewMode mode,
            @Min(1) @Max(12) Integer totalRounds,
            @Size(min = 1, max = 80) String targetJobId,
            @PositiveOrZero Long targetJobRevision,
            InterviewDifficulty difficulty,
            InterviewTraining.Topic trainingTopic,
            @Size(min = 1, max = 80) String retrySessionId,
            @Min(1) @Max(12) Integer retryRound,
            @Size(min = 1, max = 64) String questionId,
            @Positive Integer questionVersion,
            @Size(max=5) java.util.List<@NotBlank @Size(max=64) String> knowledgeDocumentIds,
            InterviewExperience.Format experienceMode,
            @Min(1) @Max(180) Integer timeLimitMinutes
    ) {
        public InterviewStartRequest(String role, String summary, Long resumeId, String jd, InterviewMode mode,
                Integer rounds, String jobId, Long revision, InterviewDifficulty difficulty,
                InterviewTraining.Topic topic, String retrySession, Integer retryRound, String questionId, Integer questionVersion,
                java.util.List<String> knowledgeIds) {
            this(role, summary, resumeId, jd, mode, rounds, jobId, revision, difficulty, topic, retrySession, retryRound,
                    questionId, questionVersion, knowledgeIds, null, null);
        }
        public InterviewStartRequest(String role, String summary, Long resumeId, String jd, InterviewMode mode,
                Integer rounds, String jobId, Long revision, InterviewDifficulty difficulty,
                InterviewTraining.Topic topic, String retrySession, Integer retryRound, String questionId, Integer questionVersion) {
            this(role, summary, resumeId, jd, mode, rounds, jobId, revision, difficulty, topic, retrySession, retryRound, questionId, questionVersion, null);
        }
        public InterviewStartRequest(String role, String summary, Long resumeId, String jd, InterviewMode mode,
                Integer rounds, String jobId, Long revision, InterviewDifficulty difficulty,
                InterviewTraining.Topic topic, String retrySession, Integer retryRound) {
            this(role, summary, resumeId, jd, mode, rounds, jobId, revision, difficulty, topic, retrySession, retryRound, null, null);
        }
        public InterviewStartRequest(String role, String summary, Long resumeId, String jd, InterviewMode mode,
                Integer rounds, String jobId, Long revision, InterviewDifficulty difficulty) {
            this(role, summary, resumeId, jd, mode, rounds, jobId, revision, difficulty, null, null, null);
        }
        public InterviewStartRequest(String role, String summary, Long resumeId) {
            this(role, summary, resumeId, null, null, null, null, null, null);
        }
    }

    /** 面试历史列表摘要；完整对话与材料通过会话详情读取。 */
    @Schema(name = "cn.edu.whu.zhizhida.interview.api.InterviewHistoryItem")
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
}
