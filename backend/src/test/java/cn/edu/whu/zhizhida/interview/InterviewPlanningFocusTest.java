package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.interview.api.*;
import cn.edu.whu.zhizhida.interview.api.InterviewAgentState.*;
import cn.edu.whu.zhizhida.interview.api.InterviewPlanning.*;
import cn.edu.whu.zhizhida.interview.domain.InterviewAgentPolicy;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class InterviewPlanningFocusTest {
    private final JsonMapper json = new JsonMapper();
    private final InterviewAgentPolicy policy = new InterviewAgentPolicy();
    private final InterviewOutputCodec codec = new InterviewOutputCodec();

    private InterviewAgentState state() {
        return policy.adaptivePlan(policy.plan(InterviewMode.GENERAL, 12, "", "model"));
    }

    private ObjectNode review(InterviewAgentState state) {
        var payload = json.createObjectNode().put("feedback", "已说明重试策略，尚需核对事务边界")
                .put("nextQuestion", "事务提交后、消息确认前崩溃，如何避免重复生效？");
        payload.putObject("agentDecision").put("level", "PARTIAL").put("evidence", "我会重试")
                .put("gap", "重试与事务的边界尚未说明").put("action", "FOLLOW_UP")
                .put("nextCompetencyId", state.activeCompetencyId()).put("reason", "回答只有重试策略")
                .put("practice", "用崩溃场景说明事务边界").put("probe", "COUNTEREXAMPLE")
                .put("intent", "验证重复消费的处理边界")
                .putObject("focus").put("dimension", "CAUSAL_EXPLANATION").put("scenario", "FAILURE")
                .put("claimToVerify", "提交与确认之间崩溃不会重复生效").put("expectedEvidence", "事务边界和重放验证结果");
        return payload;
    }

    @Test void parsesAndPersistsConcreteVerificationPlanInsteadOfAnUnstructuredReason() {
        var state = state();
        var context = InterviewAgentContext.encode("", state, policy.routing(state, 1, 12));
        var reply = codec.parseReview(review(state), context, false);
        assertThat(reply.agentDecision().focus()).isEqualTo(new Focus(Dimension.CAUSAL_EXPLANATION,
                Scenario.FAILURE, "提交与确认之间崩溃不会重复生效", "事务边界和重放验证结果"));
        var advanced = policy.advance(state, 1, 12, "遇到失败我会重试", reply.agentDecision(),
                "session", cn.edu.whu.zhizhida.common.Sha256.hex("遇到失败我会重试"), InterviewDifficulty.STANDARD, reply.nextQuestion());
        var persisted = json.readValue(json.writeValueAsString(advanced), InterviewAgentState.class);
        assertThat(persisted).isEqualTo(advanced);
        assertThat(persisted.planningRevisions().getFirst().candidates().getFirst().intent().question())
                .isEqualTo(reply.nextQuestion());
    }

    @Test void rejectsMissingInvalidOrVagueFocusOnlyForNewPlanningSessions() {
        var state = state();
        String context = InterviewAgentContext.encode("", state, policy.routing(state, 1, 12));
        for (String field : new String[]{"dimension", "scenario", "claimToVerify", "expectedEvidence"}) {
            var payload = review(state);
            ((ObjectNode)payload.path("agentDecision").path("focus")).remove(field);
            assertThatThrownBy(() -> codec.parseReview(payload, context, false)).isInstanceOf(InterviewReviewRejected.class);
        }
        for (String value : new String[]{"", " ", "x".repeat(201)}) {
            var payload = review(state);
            ((ObjectNode)payload.path("agentDecision").path("focus")).put("expectedEvidence", value);
            assertThatThrownBy(() -> codec.parseReview(payload, context, false)).isInstanceOf(InterviewReviewRejected.class);
        }
        var unknownScenario = review(state);
        ((ObjectNode)unknownScenario.path("agentDecision").path("focus")).put("scenario", "UNBOUNDED");
        assertThatThrownBy(() -> codec.parseReview(unknownScenario, context, false)).isInstanceOf(InterviewReviewRejected.class);
        var legacy = new InterviewAgentState("interview-agent-v5", state.executionMode(), state.plan(), state.activeCompetencyId(), java.util.List.of());
        var legacyPayload = review(legacy);
        ((ObjectNode)legacyPayload.path("agentDecision")).remove("focus");
        assertThat(codec.parseReview(legacyPayload, InterviewAgentContext.encode("", legacy, policy.routing(legacy, 1, 12)), false)
                .agentDecision().focus()).isNull();
        assertThatThrownBy(() -> codec.parseReview(legacyPayload, context, false)).isInstanceOf(InterviewReviewRejected.class);
    }

    @Test void anotherScenarioCanRecheckAClaimButCosmeticTextOrProbeChangesCannotRepeatIt() {
        var state = state();
        var context = InterviewAgentContext.encode("", state, policy.routing(state, 1, 12));
        var first = codec.parseReview(review(state), context, false).agentDecision();
        state = policy.advance(state, 1, 12, "我会重试", first);
        var repeated = new Decision(first.level(), first.evidence(), first.gap(), first.action(), first.nextCompetencyId(),
                first.reason(), first.practice(), Probe.EVIDENCE, first.intent(), first.questionCompetencyId(),
                new Focus(first.focus().dimension(), first.focus().scenario(), "  " + first.focus().claimToVerify() + " ", first.focus().expectedEvidence()));
        var checkpoint = state;
        assertThatThrownBy(() -> policy.advance(checkpoint, 2, 12, "我会重试", repeated)).hasMessageContaining("重复已验证目标");
        var transfer = new Decision(first.level(), first.evidence(), first.gap(), first.action(), first.nextCompetencyId(),
                first.reason(), first.practice(), first.probe(), first.intent(), first.questionCompetencyId(),
                new Focus(first.focus().dimension(), Scenario.NEW_CONTEXT, first.focus().claimToVerify(), first.focus().expectedEvidence()));
        assertThat(policy.advance(checkpoint, 2, 12, "我会重试", transfer).evaluations()).hasSize(2);
    }

    @Test void completeMockInterviewPersistsTwelveRevisionsThroughTheApplicationBoundary() {
        var ai = new MockInterviewAiService(json);
        var machine = InterviewMachineFixture.machine(null, () -> 7L, cn.edu.whu.zhizhida.ai.api.AiMode.MOCK, null, true);
        var session = new InterviewSession("planning-session", "Java", null, "", null, null, "",
                InterviewMode.GENERAL, 12, null, InterviewState.WAITING_ANSWER, 1,
                java.util.List.of(new InterviewSession.InterviewTurn("interviewer", "请介绍职责"))).withAgent(state());
        var transitions = new cn.edu.whu.zhizhida.interview.domain.InterviewTransitions();
        for (int round = 1; round <= 12; round++) {
            var pending = (cn.edu.whu.zhizhida.interview.domain.InterviewTransitions.PendingAnswer)
                    transitions.answer(session, "我负责接口设计", "request-" + round, round);
            var reply = new InterviewOutputCodec().parseReview(ai.generateReview(session.targetRole(), InterviewAgentContext.encode("", session.agent(),
                    policy.routing(session.agent(), round, 12)), pending.turns(), round, pending.finalRound()), InterviewAgentContext.encode("", session.agent(),
                    policy.routing(session.agent(), round, 12)), pending.finalRound(), pending.turns());
            session = machine.validateReview(session, pending, reply);
            assertThat(session.agent().planningRevisions()).hasSize(round);
            if (round < 12) {
                var source = session.agent().planningRevisions().getLast().candidates().getFirst().gap();
                assertThat(source.sourceId()).isEqualTo("planning-session:answer:" + round);
                assertThat(source.sourceVersion()).isEqualTo(cn.edu.whu.zhizhida.common.Sha256.hex("我负责接口设计"));
            }
        }
        assertThat(session.state()).isEqualTo(InterviewState.FINISHED);
        assertThat(session.agent().planningRevisions().getLast().candidates()).isEmpty();
        assertThat(session.agent().evaluations().stream().map(Evaluation::competencyId).distinct()).hasSize(session.agent().plan().size());
    }

    @Test void sixRoundFocusedTrainingReplansWithoutLosingItsScopeOrSnapshot() {
        var training = new InterviewTraining(InterviewTraining.Topic.DATABASE, null, null);
        var fixed = policy.trainingPlan(training, new InterviewPlanningContext("", java.util.List.of(), java.util.List.of(), java.util.List.of(), ""),
                "mock", java.util.List.of(), null);
        var adaptive = policy.adaptivePlan(fixed);
        assertThat(adaptive.training()).isEqualTo(training);
        assertThat(adaptive.plan()).extracting(Competency::id).containsExactly("storage");
        var ai = new MockInterviewAiService(json);
        var machine = InterviewMachineFixture.machine(null, () -> 7L, cn.edu.whu.zhizhida.ai.api.AiMode.MOCK, null, true);
        var session = new InterviewSession("training-session", "Java", null, "", null, null, "",
                InterviewMode.BACKEND_BASICS, 6, null, InterviewState.WAITING_ANSWER, 1,
                java.util.List.of(new InterviewSession.InterviewTurn("interviewer", "事务如何保证一致性？"))).withAgent(adaptive);
        var transitions = new cn.edu.whu.zhizhida.interview.domain.InterviewTransitions();
        for (int round = 1; round <= 6; round++) {
            var pending = (cn.edu.whu.zhizhida.interview.domain.InterviewTransitions.PendingAnswer)
                    transitions.answer(session, "我使用数据库事务处理一致性", "request-" + round, round);
            var route = policy.routing(session.agent(), round, 6);
            assertThat(route.eligibleNext()).isEmpty();
            assertThat(route.allowedActions()).doesNotContain(Action.ADVANCE);
            var reply = new InterviewOutputCodec().parseReview(ai.generateReview(session.targetRole(), InterviewAgentContext.encode("", session.agent(), route), pending.turns(), round, pending.finalRound()), InterviewAgentContext.encode("", session.agent(), route), pending.finalRound(), pending.turns());
            session = machine.validateReview(session, pending, reply);
        }
        assertThat(session.state()).isEqualTo(InterviewState.FINISHED);
        assertThat(session.agent().training()).isEqualTo(training);
        assertThat(session.agent().planningRevisions()).hasSize(6);
        assertThat(session.agent().evaluations()).allMatch(e -> e.competencyId().equals("storage"));
    }
}
