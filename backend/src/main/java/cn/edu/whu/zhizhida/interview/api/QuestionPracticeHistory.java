package cn.edu.whu.zhizhida.interview.api;

import cn.edu.whu.zhizhida.common.PageRequest;
import cn.edu.whu.zhizhida.common.PageResult;
import java.time.Instant;
import java.util.List;

/** 读取当前账号的题目练习进度与分页历史，供选题与复练使用；不修改题目或训练状态。 */
public interface QuestionPracticeHistory {
    enum Status { UNPRACTICED, PRACTICED, NEEDS_REVIEW }
    record Progress(String questionId, Status status, long attempts, String lastSessionId, Instant lastPracticedAt) {}
    record Round(int round, String question, String answer, String feedback,
                 InterviewAgentState.Level level, String evidence, String gap) {}
    record Practice(String sessionId, QuestionSnapshot question, InterviewDifficulty difficulty,
                    InterviewState state, Instant createdAt, Instant updatedAt, List<Round> rounds) {}
    List<Progress> progress();
    PageResult<Practice> history(String questionId, PageRequest page);
}
