package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.interview.api.*;
import cn.edu.whu.zhizhida.interview.api.InterviewAgentState.*;
import cn.edu.whu.zhizhida.interview.api.InterviewPlanning.*;
import cn.edu.whu.zhizhida.interview.api.InterviewSession.InterviewTurn;
import cn.edu.whu.zhizhida.interview.domain.InterviewAgentPolicy;
import cn.edu.whu.zhizhida.interview.domain.InterviewTransitions;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import cn.edu.whu.zhizhida.resume.api.ResumeSnapshotReader;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class InterviewPlannerRetirementTest {
    private final JsonMapper json = new JsonMapper();
    private final InterviewAgentPolicy policy = new InterviewAgentPolicy();

    @Test
    void historicalV4SessionContinuesWithFixedRoutingAndPreservesPlanningEvidence() {
        var plan = policy.plan(InterviewMode.GENERAL, 6, "", "mock");
        var v4 = new InterviewAgentState("interview-agent-v4", "mock", plan.plan(), plan.activeCompetencyId(), List.of());
        var decision = new Decision(Level.PARTIAL, "我负责接口设计", "验证依据不足", Action.FOLLOW_UP,
                "experience", "核对个人行动", "补充验证依据", Probe.EVIDENCE, "验证个人职责与结果", "experience");
        var intent = new QuestionIntent("experience", Probe.EVIDENCE, Dimension.RESPONSIBILITY, Scenario.CURRENT,
                "个人职责", "验证记录", "我负责接口设计", 0, 7, InterviewDifficulty.STANDARD, "你如何验证接口设计的结果？");
        var gap = new EvidenceGap("historical-gap", Dimension.RESPONSIBILITY, GapStatus.NEEDS_VERIFICATION,
                "个人职责", "answer:1", "historical-version", "utf16-v1", 0, 7, "我负责接口设计");
        var revision = new PlanRevision(1, 1, "intent-planner-v4.1", InterviewDifficulty.STANDARD,
                List.of(new Candidate("experience/EVIDENCE", intent, gap, 1)), List.of(),
                "experience/EVIDENCE", false, "历史规划选择记录");
        var answered = policy.advance(v4, 1, 6, "我负责接口设计", decision);
        v4 = new InterviewAgentState(v4.version(), v4.executionMode(), v4.plan(), answered.activeCompetencyId(),
                answered.evaluations(), answered.digest(), answered.practiceMemory(), null, List.of(), List.of(revision));
        var saved = new InterviewSession("historical-v4", "Java", null, "", null, null, "",
                InterviewMode.GENERAL, 6, null, InterviewState.WAITING_ANSWER, 2,
                List.of(new InterviewTurn("interviewer", "请介绍个人职责"),
                        new InterviewTurn("user", "我负责接口设计", "old-request"),
                        new InterviewTurn("system", "即时反馈：请补充依据"),
                        new InterviewTurn("interviewer", "你如何验证接口设计的结果？"))).withAgent(v4);
        var restored = json.readValue(json.writeValueAsString(saved), InterviewSession.class);
        assertThat(restored).isEqualTo(saved);
        var v3 = new InterviewAgentState("interview-agent-v3", "mock", v4.plan(), v4.activeCompetencyId(), v4.evaluations());
        assertThat(policy.routing(restored.agent(), 2, 6)).isEqualTo(policy.routing(v3, 2, 6));

        var pending = (InterviewTransitions.PendingAnswer) new InterviewTransitions().answer(restored,
                "我负责接口设计，并核对失败恢复结果。", "new-request", 2);
        var ai = new MockInterviewAiService(json, BusinessMetrics.disabled());
        var reply = ai.reviewAnswer("Java", InterviewAgentContext.encode("", restored.agent(),
                policy.routing(restored.agent(), 2, 6)), pending.turns(), 2, false);
        var machine = InterviewMachineFixture.machine(mock(InterviewSessionRepository.class), () -> 7L,
                ai, mock(ResumeSnapshotReader.class));
        var continued = machine.validateReview(restored, pending, reply);

        assertThat(continued.turnCount()).isEqualTo(3);
        assertThat(continued.turns().getLast().content()).isEqualTo(reply.nextQuestion());
        assertThat(continued.agent().version()).isEqualTo("interview-agent-v4");
        assertThat(continued.agent().evaluations()).hasSize(2);
        assertThat(continued.agent().planningRevisions()).containsExactly(revision);
        var replay = new InterviewTransitions().replay(continued, "我负责接口设计", "old-request", 1).orElseThrow();
        assertThat(replay.agent().planningRevisions()).containsExactly(revision);
        assertThat(replay.agent().evaluations()).hasSize(1);
    }

    @Test
    void retiredProposalCannotOverrideOrInvalidateANewQuestion() {
        var v3 = policy.plan(InterviewMode.GENERAL, 6, "", "mock");
        var v4 = new InterviewAgentState("interview-agent-v4", "mock", v3.plan(), v3.activeCompetencyId(), List.of());
        var context = InterviewAgentContext.encode("", v4, policy.routing(v4, 1, 6));
        var turns = List.of(new InterviewTurn("user", "我负责接口设计"));
        var ai = new MockInterviewAiService(json, BusinessMetrics.disabled());
        var generated = ai.generateReview("Java", context, turns, 1, false);
        var payload = (tools.jackson.databind.node.ObjectNode) generated.deepCopy();
        payload.put("planProposal", "obsolete malformed proposal");

        var parsed = new InterviewOutputCodec().parseReview(payload, context, false, turns);
        assertThat(json.valueToTree(parsed).has("planProposal")).isFalse();
        assertThat(parsed.nextQuestion()).isEqualTo(generated.path("nextQuestion").asText());
        assertThat(parsed.agentDecision()).isNotNull();
    }
}
