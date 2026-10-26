package cn.edu.whu.zhizhida.interview.domain;

import cn.edu.whu.zhizhida.interview.api.InterviewMode;
import cn.edu.whu.zhizhida.interview.api.InterviewSession;
import cn.edu.whu.zhizhida.interview.api.InterviewState;
import cn.edu.whu.zhizhida.interview.api.InterviewTurn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 纯规则测试无需应用容器、模拟框架、模型或数据库。 */
class InterviewTransitionsTest {
    private final InterviewTransitions transitions = new InterviewTransitions();

    @Test
    void defaultsToThreeRounds() {
        assertThat(transitions.configuredRounds(null)).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 13, Integer.MAX_VALUE})
    void rejectsUnsupportedRoundCounts(int rounds) {
        assertThatThrownBy(() -> transitions.configuredRounds(rounds))
                .isInstanceOf(InterviewRuleViolation.class).hasMessageContaining("1 至 12");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 6, 12})
    void advancesExactlyConfiguredRoundsAndReplaysEveryHistoricalResult(int rounds) {
        assertThat(transitions.configuredRounds(rounds)).isEqualTo(rounds);
        InterviewSession current = session(rounds);
        List<InterviewSession> responses = new ArrayList<>();
        for (int round = 1; round <= rounds; round++) {
            var pending = pending(current, "  回答" + round + "  ", "request-" + round, round);
            assertThat(pending.answeredCount()).isEqualTo(round);
            assertThat(pending.finalRound()).isEqualTo(round == rounds);
            assertThat(pending.turns()).hasSize(current.turns().size() + 1);
            assertThat(pending.turns().get(pending.turns().size() - 1))
                    .isEqualTo(new InterviewTurn("user", "回答" + round, "request-" + round));

            InterviewSession next = pending.complete("反馈" + round, "追问" + round, "总结");
            assertThat(next.state()).isEqualTo(round == rounds ? InterviewState.FINISHED : InterviewState.WAITING_ANSWER);
            assertThat(next.turnCount()).isEqualTo(Math.min(round + 1, rounds));
            assertThat(next.turns()).hasSize(2 + 3 * round);
            assertThat(next.turns().get(next.turns().size() - 2).content()).isEqualTo("即时反馈：反馈" + round);
            assertThat(next.turns().get(next.turns().size() - 1).content()).isEqualTo(round == rounds ? "总结" : "追问" + round);
            assertThat(next).usingRecursiveComparison().ignoringFields("state", "turnCount", "turns", "answerLeaseUntil")
                    .isEqualTo(current);
            assertThat(next.answerLeaseUntil()).isNull();
            assertThat(current.turns()).hasSize(2 + 3 * (round - 1));
            responses.add(next);
            current = next;
        }
        for (int round = 1; round <= rounds; round++) {
            var replay = transitions.answer(current, " 回答" + round + " ", "request-" + round, round);
            assertThat(replay).isEqualTo(new InterviewTransitions.ExistingAnswer(responses.get(round - 1)));
        }
        assertThat(transitions.answer(current, "额外回答", "new", 99))
                .isEqualTo(new InterviewTransitions.ExistingAnswer(current));
    }

    @Test
    void refusesConflictingPayloadOrRoundEvenAfterSessionFinished() {
        InterviewSession finished = pending(session(1), "回答", "request-1", 1).complete("反馈", "", "总结");
        assertThatThrownBy(() -> transitions.answer(finished, "不同回答", "request-1", 1))
                .isInstanceOf(InterviewRuleViolation.class).hasMessageContaining("请求编号");
        assertThatThrownBy(() -> transitions.answer(finished, "回答", "request-1", 2))
                .isInstanceOf(InterviewRuleViolation.class).hasMessageContaining("请求编号");
    }

    @Test
    void rejectsStaleQuestionBeforePreparingAnAnswer() {
        InterviewSession next = pending(session(3), "回答", "first", 1).complete("反馈", "追问", "");
        assertThatThrownBy(() -> transitions.answer(next, "迟到回答", "second", 1))
                .isInstanceOf(InterviewRuleViolation.class).hasMessageContaining("题目已更新");
        assertThat(next.turnCount()).isEqualTo(2);
    }

    @Test
    void supportsLegacyAnswersWithoutRequestIdOrExpectedTurn() {
        InterviewSession next = pending(session(3), "回答", null, null).complete("反馈", "追问", "");
        assertThat(next.turnCount()).isEqualTo(2);
        assertThat(next.turns().get(2).requestId()).isNull();
        assertThat(transitions.replay(next, "回答", null, null)).isEmpty();
        assertThat(transitions.replay(next, "回答", "unknown", null)).isEmpty();
        InterviewSession keyed = pending(session(3), "回答", "key", null).complete("反馈", "追问", "");
        assertThat(transitions.replay(keyed, "回答", "key", null)).contains(keyed);
    }

    @Test
    void cannotReplayAnAnswerWithoutItsFeedbackAndNextMessage() {
        var incomplete = new InterviewSession("session", "Java", null, "", InterviewState.WAITING_ANSWER, 1,
                List.of(new InterviewTurn("interviewer", "问题"), new InterviewTurn("user", "回答", "key")));
        assertThatThrownBy(() -> transitions.replay(incomplete, "回答", "key", 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("记录不完整");
    }

    @Test
    void preparationIsAnImmutableSnapshotAndCompletionIsDeterministic() {
        List<InterviewTurn> input = new ArrayList<>(session(3).turns());
        var source = new InterviewSession("session", "Java", null, "", InterviewState.WAITING_ANSWER, 1, input);
        var pending = pending(source, "回答", "key", 1);
        input.clear();
        assertThat(pending.turns()).hasSize(3);
        assertThatThrownBy(() -> pending.turns().clear()).isInstanceOf(UnsupportedOperationException.class);
        var result = pending.complete("反馈", "追问", "忽略的提前总结");
        assertThat(result).isEqualTo(pending.complete("反馈", "追问", "另一份提前总结"));
        assertThatThrownBy(() -> result.turns().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(result.turns()).hasSize(5);
        assertThat(result.state()).isEqualTo(InterviewState.WAITING_ANSWER);
    }

    private InterviewTransitions.PendingAnswer pending(InterviewSession source, String answer, String key, Integer turn) {
        var decision = transitions.answer(source, answer, key, turn);
        assertThat(decision).isInstanceOf(InterviewTransitions.PendingAnswer.class);
        return (InterviewTransitions.PendingAnswer) decision;
    }

    private InterviewSession session(int rounds) {
        return new InterviewSession("session", "Java 后端", 7L, "简历快照", 5L, 9L, "JD 快照",
                InterviewMode.PROJECT_DEEP_DIVE, rounds, Instant.parse("2026-09-30T00:00:00Z"),
                InterviewState.WAITING_ANSWER, 1,
                List.of(new InterviewTurn("system", "开始"), new InterviewTurn("interviewer", "第一题")));
    }
}
