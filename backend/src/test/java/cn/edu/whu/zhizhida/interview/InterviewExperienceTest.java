package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.interview.api.InterviewSession.InterviewTurn;

import cn.edu.whu.zhizhida.interview.api.*;
import cn.edu.whu.zhizhida.interview.domain.InterviewTransitions;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class InterviewExperienceTest {
    @Test void formalProjectionHidesFeedbackOnEveryReplayButRetainsFullReviewAtFinish() {
        var experience=new InterviewExperience(InterviewExperience.Format.FORMAL,20,Instant.parse("2026-10-29T01:00:00Z"));
        var agent=new InterviewAgentState("v3","mock",List.of(new InterviewAgentState.Competency("storage","数据库","内部考察目标")),"storage",List.of());
        var start=new InterviewSession("id","后端",null,"",null,null,"",InterviewMode.GENERAL,2,null,
                InterviewState.WAITING_ANSWER,1,List.of(new InterviewTurn("system","开始"),new InterviewTurn("interviewer","首问")),null,agent)
                .withExperience(experience);
        var rules=new InterviewTransitions();
        var one=((InterviewTransitions.PendingAnswer)rules.answer(start,"回答一","request-1",1)).complete("反馈一","下一题","");
        assertThat(one.experience()).isEqualTo(experience);
        assertThat(one.publicView().agent()).isNull();
        assertThat(one.publicView().turns()).extracting(InterviewTurn::content).doesNotContain("即时反馈：反馈一");
        assertThat(one.turns()).extracting(InterviewTurn::content).contains("即时反馈：反馈一");
        var end=((InterviewTransitions.PendingAnswer)rules.answer(one,"回答二","request-2",2)).complete("反馈二","","统一复盘");
        assertThat(end.publicView()).isSameAs(end);
        assertThat(end.turns()).extracting(InterviewTurn::content).contains("即时反馈：反馈一","即时反馈：反馈二","统一复盘");
        var replay=rules.replay(end,"回答一","request-1",1).orElseThrow().publicView();
        assertThat(replay.agent()).isNull();
        assertThat(replay.turns()).extracting(InterviewTurn::content).doesNotContain("即时反馈：反馈一","即时反馈：反馈二");
        assertThat(replay.experience()).isEqualTo(experience);
    }
    @Test void teachingAndLegacySessionsKeepImmediateFeedback() {
        var old=new InterviewSession("id","后端",null,"",InterviewState.WAITING_ANSWER,1,List.of(new InterviewTurn("system","即时反馈：继续")));
        assertThat(old.publicView()).isSameAs(old);
        var teaching=old.withExperience(new InterviewExperience(null,null,Instant.now()));
        assertThat(teaching.publicView()).isSameAs(teaching);
    }
}
