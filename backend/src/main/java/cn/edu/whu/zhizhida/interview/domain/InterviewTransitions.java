package cn.edu.whu.zhizhida.interview.domain;

import cn.edu.whu.zhizhida.interview.api.InterviewSession;
import cn.edu.whu.zhizhida.interview.api.InterviewState;
import cn.edu.whu.zhizhida.interview.api.InterviewTurn;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 基于会话快照决定回答回放、题号冲突和轮次推进的确定性规则。
 * 不访问身份、时钟、模型或数据库；待评审回答与已提交结果通过不同决策类型区分。
 */
public final class InterviewTransitions {

    public int configuredRounds(Integer requested) {
        int rounds = requested == null ? 3 : requested;
        if (rounds < 1 || rounds > 12) {
            throw new InterviewRuleViolation("面试轮数仅支持 1 至 12 轮。");
        }
        return rounds;
    }

    public AnswerDecision answer(InterviewSession session, String answer, String requestId, Integer expectedTurn) {
        Optional<InterviewSession> replay = replay(session, answer, requestId, expectedTurn);
        if (replay.isPresent()) return new ExistingAnswer(replay.get());
        // 保持既有接口语义：对已结束会话提交新回答时，返回已保存的会话结果。
        if (session.state() == InterviewState.FINISHED) return new ExistingAnswer(session);
        if (expectedTurn != null && expectedTurn != session.turnCount()) {
            throw new InterviewRuleViolation("面试题目已更新，请重新打开当前会话后回答。");
        }
        return new PendingAnswer(session, answer, requestId);
    }

    /** 只查找已提交的回答；领取租约失败后可调用此方法回放，但不能继续创建新回答。 */
    public Optional<InterviewSession> replay(InterviewSession session, String answer,
                                              String requestId, Integer expectedTurn) {
        if (requestId == null) return Optional.empty();
        int answered = 0;
        for (int i = 0; i < session.turns().size(); i++) {
            InterviewTurn turn = session.turns().get(i);
            if (!"user".equals(turn.speaker())) continue;
            answered++;
            if (!requestId.equals(turn.requestId())) continue;
            if (!answer.trim().equals(turn.content()) || (expectedTurn != null && expectedTurn != answered)) {
                throw new InterviewRuleViolation("该请求编号已用于另一份回答，请勿重复使用。");
            }
            if (i + 2 >= session.turns().size()) {
                throw new IllegalStateException("面试记录不完整，请稍后重试。");
            }
            // 截止到该回答的反馈与下一题返回当时结果，避免重试混入后来产生的轮次。
            return Optional.of(withProgress(session,
                    answered >= session.totalRounds() ? InterviewState.FINISHED : InterviewState.WAITING_ANSWER,
                    Math.min(answered + 1, session.totalRounds()), session.turns().subList(0, i + 3)));
        }
        return Optional.empty();
    }

    public sealed interface AnswerDecision permits ExistingAnswer, PendingAnswer { }

    public record ExistingAnswer(InterviewSession session) implements AnswerDecision { }

    /** 通过规则检查的待评审快照；应用服务仍需取得租约并成功提交，才能成为持久化结果。 */
    public static final class PendingAnswer implements AnswerDecision {
        private final InterviewSession source;
        private final List<InterviewTurn> turns;
        private final int answeredCount;

        private PendingAnswer(InterviewSession source, String answer, String requestId) {
            this.source = source;
            List<InterviewTurn> proposed = new ArrayList<>(source.turns());
            proposed.add(new InterviewTurn("user", answer.trim(), requestId));
            this.turns = List.copyOf(proposed);
            this.answeredCount = (int) turns.stream().filter(turn -> "user".equals(turn.speaker())).count();
        }

        public List<InterviewTurn> turns() {
            return turns;
        }

        public int answeredCount() {
            return answeredCount;
        }

        public boolean finalRound() {
            return answeredCount >= source.totalRounds();
        }

        /** 模型只提供反馈、问题与总结文本；下一状态和轮次始终由本地规则计算。 */
        public InterviewSession complete(String feedback, String nextQuestion, String finalSummary) {
            List<InterviewTurn> completed = new ArrayList<>(turns);
            completed.add(new InterviewTurn("system", "即时反馈：" + feedback));
            completed.add(new InterviewTurn("interviewer", finalRound() ? finalSummary : nextQuestion));
            return withProgress(source, finalRound() ? InterviewState.FINISHED : InterviewState.WAITING_ANSWER,
                    finalRound() ? source.turnCount() : answeredCount + 1, completed);
        }
    }

    private static InterviewSession withProgress(InterviewSession source, InterviewState state,
                                                  int turnCount, List<InterviewTurn> turns) {
        var preparation = source.preparation();
        if (preparation != null) preparation = preparation.withFollowUps(
                state == InterviewState.FINISHED ? ProjectFollowUps.from(turns) : List.of());
        return new InterviewSession(source.sessionId(), source.targetRole(), source.resumeId(),
                source.resumeSnapshot(), source.resumeRevision(), source.jobId(), source.jobSnapshot(),
                source.mode(), source.totalRounds(), null, state, turnCount, List.copyOf(turns), preparation,
                source.agent() == null ? null : source.agent().through(
                        (int) turns.stream().filter(t -> "user".equals(t.speaker())).count()), source.difficulty());
    }
}
