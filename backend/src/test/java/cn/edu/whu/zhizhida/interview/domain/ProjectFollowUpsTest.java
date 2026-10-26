package cn.edu.whu.zhizhida.interview.domain;

import cn.edu.whu.zhizhida.interview.api.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ProjectFollowUpsTest {
    @Test void finalReviewContainsExactQuestionAnswerAndFeedbackAndReplayDoesNotDuplicate() {
        var preparation = new InterviewPreparation("a", null, "details.projects[0].contributions", null, null,
                List.of("说明选择理由"), List.of());
        var initial = new InterviewSession("s", "Java", 1L, "简历", 2L, null, "JD", InterviewMode.PROJECT_DEEP_DIVE,
                2, null, InterviewState.WAITING_ANSWER, 1,
                List.of(new InterviewTurn("system", "开始"), new InterviewTurn("interviewer", "为什么选缓存？")), preparation);
        var rules = new InterviewTransitions();
        var first = ((InterviewTransitions.PendingAnswer) rules.answer(initial, "减少读取", "one", 1))
                .complete("请补充约束", "有何代价？", "");
        assertThat(first.preparation().followUps()).isEmpty();
        var finished = ((InterviewTransitions.PendingAnswer) rules.answer(first, "短暂延迟", "two", 2))
                .complete("补充验证", "", "已结束");
        assertThat(finished.preparation().followUps()).containsExactly(
                new InterviewPreparation.FollowUp(1, "为什么选缓存？", "减少读取", "即时反馈：请补充约束"),
                new InterviewPreparation.FollowUp(2, "有何代价？", "短暂延迟", "即时反馈：补充验证"));
        assertThat(rules.replay(finished, "短暂延迟", "two", 2).orElseThrow()).isEqualTo(finished);
        assertThat(rules.replay(finished, "减少读取", "one", 1).orElseThrow().preparation().followUps()).isEmpty();
        assertThat(initial.preparation().followUps()).isEmpty();
    }

    @Test void followUpListsAreImmutableAndNeverMistakeTheSummaryForAQuestion() {
        var turns = new ArrayList<>(List.of(new InterviewTurn("interviewer", "项目如何验证？"),
                new InterviewTurn("user", "暂未验证"), new InterviewTurn("system", "请补充测试条件"),
                new InterviewTurn("interviewer", "最终总结，不是问题")));
        var notes = ProjectFollowUps.from(turns);
        turns.clear();
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).question()).isEqualTo("项目如何验证？");
        assertThatThrownBy(() -> notes.clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
