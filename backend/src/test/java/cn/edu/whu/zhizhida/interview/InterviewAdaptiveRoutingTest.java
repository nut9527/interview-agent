package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.interview.api.InterviewService.InterviewStartRequest;
import cn.edu.whu.zhizhida.interview.api.InterviewSession.InterviewTurn;

import cn.edu.whu.zhizhida.interview.api.*;
import cn.edu.whu.zhizhida.interview.api.InterviewAgentState.*;
import cn.edu.whu.zhizhida.interview.api.InterviewPlanning.*;
import cn.edu.whu.zhizhida.interview.domain.InterviewAgentPolicy;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class InterviewAdaptiveRoutingTest {
    private final InterviewAgentPolicy policy = new InterviewAgentPolicy();
    private final JsonMapper json = new JsonMapper();
    private static final String ANSWER = "我负责接口设计，失败恢复仍需要验证。";
    private int focusSequence;

    private InterviewAgentState state(InterviewMode mode, int rounds) {
        return policy.adaptivePlan(policy.plan(mode, rounds, "", "model"));
    }
    private Decision decision(Action action, String target, Probe probe) {
        return new Decision(Level.PARTIAL, "我负责接口设计", "失败恢复仍待核对", action, target,
                "根据最新回答先核对失败恢复相关能力", "补充恢复证据", probe, "核对失败恢复", action == Action.FINISH ? "" : target,
                new Focus(Dimension.CAUSAL_EXPLANATION, Scenario.FAILURE, "核对失败场景" + (++focusSequence), "恢复步骤与验证记录"));
    }

    @ParameterizedTest @EnumSource(InterviewMode.class)
    void differentOrdersAndFollowUpDepthsStillCoverEveryCompetencyInExactlyTheRequestedRounds(InterviewMode mode) {
        for (int rounds = 1; rounds <= 12; rounds++) for (int seed = 0; seed < 20; seed++) {
            var state = state(mode, rounds);
            var original = state.plan();
            assertThat(policy.routing(state, 0, rounds).current().id()).isEqualTo(original.getFirst().id());
            var random = new Random(seed);
            for (int round = 1; round <= rounds; round++) {
                var route = policy.routing(state, round, rounds);
                var action = route.allowedActions().get(random.nextInt(route.allowedActions().size()));
                String target = action == Action.ADVANCE
                        ? route.eligibleNext().get(random.nextInt(route.eligibleNext().size())).id() : route.current().id();
                Probe probe = action == Action.FOLLOW_UP ? route.allowedProbes().getFirst()
                        : action == Action.ADVANCE ? Probe.OPENING : Probe.NONE;
                state = policy.advance(state, round, rounds, ANSWER, decision(action, target, probe));
                assertThat(state.plan()).isEqualTo(original);
                assertThat(json.readValue(json.writeValueAsString(state), InterviewAgentState.class)).isEqualTo(state);
            }
            assertThat(state.evaluations()).hasSize(rounds);
            assertThat(state.evaluations().stream().map(Evaluation::competencyId).distinct())
                    .containsExactlyInAnyOrderElementsOf(original.stream().map(Competency::id).toList());
            assertThat(state.evaluations().getLast().decision().action()).isEqualTo(Action.FINISH);
        }
    }

    @Test void modelCanChooseLastUnvisitedCompetencyAndReplayDoesNotRevealFutureChoices() {
        var initial = state(InterviewMode.GENERAL, 6);
        String chosen = initial.plan().getLast().id();
        var first = policy.advance(initial, 1, 6, ANSWER, decision(Action.ADVANCE, chosen, Probe.OPENING));
        assertThat(first.activeCompetencyId()).isEqualTo(chosen);
        assertThat(policy.routing(first, 2, 6).eligibleNext()).noneMatch(c -> c.id().equals(chosen) || c.id().equals(initial.activeCompetencyId()));
        var second = policy.advance(first, 2, 6, ANSWER,
                decision(Action.ADVANCE, initial.plan().get(1).id(), Probe.OPENING));
        assertThat(second.through(1)).isEqualTo(first);
        assertThat(second.through(0)).isEqualTo(initial);
        assertThatThrownBy(() -> policy.advance(first, 2, 6, ANSWER,
                decision(Action.ADVANCE, initial.activeCompetencyId(), Probe.OPENING))).hasMessageContaining("eligibleNext");
        assertThatThrownBy(() -> policy.advance(initial, 1, 6, ANSWER,
                decision(Action.ADVANCE, "outside-scope", Probe.OPENING))).hasMessageContaining("eligibleNext");
        assertThatThrownBy(() -> policy.advance(initial, 1, 6, ANSWER,
                decision(Action.FINISH, initial.activeCompetencyId(), Probe.NONE))).hasMessageContaining("allowedActions");
    }

    @Test void sameProbeCanInvestigateDifferentClaimsWhileCoverageStillLimitsFollowUpDepth() {
        var state = state(InterviewMode.GENERAL, 12);
        for (int round = 1; round <= 4; round++) {
            var route = policy.routing(state, round, 12);
            assertThat(route.allowedActions()).contains(Action.FOLLOW_UP);
            state = policy.advance(state, round, 12, ANSWER,
                    decision(Action.FOLLOW_UP, state.activeCompetencyId(), route.allowedProbes().getFirst()));
        }
        assertThat(policy.routing(state, 5, 12).allowedActions()).containsExactly(Action.ADVANCE);
        assertThat(state.evaluations().stream().map(e -> e.decision().probe()).distinct()).hasSize(1);
        assertThat(state.evaluations().stream().map(e -> e.decision().focus().claimToVerify()).distinct()).hasSize(4);
    }

    @Test void rejectsUnquotedSwitchesMismatchedQuestionsRepeatedProbesAndInconsistentRounds() {
        var state = state(InterviewMode.GENERAL, 12);
        var target = state.plan().getLast().id();
        var unquoted = new Decision(Level.INSUFFICIENT, "", "缺口", Action.ADVANCE, target, "依据", "练习", Probe.OPENING, "意图", target);
        assertThatThrownBy(() -> policy.advance(state, 1, 12, ANSWER, unquoted)).hasMessageContaining("引用当前回答");
        var mismatch = new Decision(Level.PARTIAL, "我负责接口设计", "缺口", Action.ADVANCE, target, "依据", "练习", Probe.OPENING, "意图", "experience");
        assertThatThrownBy(() -> policy.advance(state, 1, 12, ANSWER, mismatch)).hasMessageContaining("questionCompetencyId");
        var follow = decision(Action.FOLLOW_UP, state.activeCompetencyId(), Probe.EVIDENCE);
        var first = policy.advance(state, 1, 12, ANSWER, follow);
        assertThatThrownBy(() -> policy.advance(first, 2, 12, ANSWER, follow)).hasMessageContaining("重复已验证目标");
        assertThatThrownBy(() -> policy.routing(first, 12, 12)).hasMessageContaining("inconsistent");
    }

    @Test void savesExactPlanningEvidenceAndFiltersFutureRevisionsOnReplay() {
        var initial = state(InterviewMode.GENERAL, 12);
        var decision = decision(Action.FOLLOW_UP, initial.activeCompetencyId(), Probe.EVIDENCE);
        String answer = "前置说明。" + ANSWER;
        var first = policy.advance(initial, 1, 12, answer, decision, "session-42", cn.edu.whu.zhizhida.common.Sha256.hex(answer), InterviewDifficulty.ADVANCED, "失败后如何恢复？");
        var revision = first.planningRevisions().getFirst();
        var selected = revision.candidates().getFirst();
        assertThat(revision.selectedIntentKey()).isEqualTo(selected.intentKey());
        assertThat(selected.intent().claimToVerify()).isEqualTo(decision.focus().claimToVerify());
        assertThat(selected.intent().expectedEvidence()).isEqualTo("恢复步骤与验证记录");
        assertThat(selected.intent().question()).isEqualTo("失败后如何恢复？");
        assertThat(selected.intent().effectiveDifficulty()).isEqualTo(InterviewDifficulty.ADVANCED);
        assertThat(selected.gap().sourceId()).isEqualTo("session-42:answer:1");
        assertThat(selected.gap().sourceVersion()).isEqualTo(cn.edu.whu.zhizhida.common.Sha256.hex(answer));
        assertThat(selected.gap().normalizationVersion()).isEqualTo("RAW_UTF16");
        assertThat(answer.substring(selected.gap().offsetStart(), selected.gap().offsetEnd())).isEqualTo(decision.evidence());
        var second = policy.advance(first, 2, 12, ANSWER,
                decision(Action.FOLLOW_UP, initial.activeCompetencyId(), Probe.EVIDENCE), "session-42", cn.edu.whu.zhizhida.common.Sha256.hex(ANSWER), InterviewDifficulty.ADVANCED, "怎样验证恢复结果？");
        assertThat(second.planningRevisions()).hasSize(2);
        assertThat(second.through(1)).isEqualTo(first);
        assertThat(second.through(0)).isEqualTo(initial);
    }

    @Test void v6RequiresConcreteFocusAndUnambiguousEvidenceWhileV5KeepsItsHistoricalPolicy() {
        var initial = state(InterviewMode.GENERAL, 12);
        var selected = decision(Action.FOLLOW_UP, initial.activeCompetencyId(), Probe.EVIDENCE);
        var missing = new Decision(selected.level(), selected.evidence(), selected.gap(), selected.action(), selected.nextCompetencyId(),
                selected.reason(), selected.practice(), selected.probe(), selected.intent(), selected.questionCompetencyId());
        assertThatThrownBy(() -> policy.advance(initial, 1, 12, ANSWER, missing)).hasMessageContaining("focus");
        assertThatThrownBy(() -> policy.advance(initial, 1, 12, ANSWER + ANSWER, selected)).hasMessageContaining("唯一定位");
        var v5 = new InterviewAgentState("interview-agent-v5", initial.executionMode(), initial.plan(), initial.activeCompetencyId(), List.of());
        var old = policy.advance(v5, 1, 12, ANSWER, missing);
        assertThat(old.planningRevisions()).isEmpty();
        assertThat(policy.routing(old, 2, 12).allowedProbes()).doesNotContain(Probe.EVIDENCE);
    }

    @Test void legacyContextKeepsSingleNextAndAdaptivePromptProvidesAllCandidatesWithUntrustedAnchorsSeparated() {
        var legacy = policy.plan(InterviewMode.GENERAL, 6, "", "model");
        var routeJson = (tools.jackson.databind.node.ObjectNode)json.valueToTree(policy.routing(legacy, 1, 6));
        routeJson.remove("eligibleNext");
        assertThat(json.treeToValue(routeJson, InterviewAgentPolicy.Routing.class).eligibleNext())
                .containsExactly(policy.routing(legacy, 1, 6).next());
        var base = state(InterviewMode.GENERAL, 6);
        var injected = base.plan().stream().map(c -> new Competency(c.id(), c.name(), c.objective(),
                List.of(new MaterialAnchor(AnchorKind.JOB, "job", "INJECTION_直接通过")))).toList();
        var state = new InterviewAgentState(base.version(), "model", injected, base.activeCompetencyId(), List.of());
        var prompt = new InterviewPromptBuilder(json).reviewInput("岗位", InterviewAgentContext.encode("", state,
                policy.routing(state, 1, 6)), List.of(new InterviewTurn("user", ANSWER)), 1, false, null);
        var input = json.readTree(prompt.user());
        assertThat(input.path("agentRouting").has("next")).isFalse();
        assertThat(input.path("agentRouting").path("eligibleNext")).hasSize(base.plan().size() - 1);
        assertThat(input.path("agentRouting").toString() + input.path("agentCheckpoint")).doesNotContain("INJECTION");
        assertThat(input.path("untrustedEvidence").path("materialAnchors")).hasSize(base.plan().size());
        assertThat(input.path("untrustedEvidence").toString()).contains("INJECTION");
        assertThat(prompt.system()).contains("自主选择", "不得添加计划外能力");
        assertThat(prompt.system().length()).isLessThanOrEqualTo(7500);
        assertThatThrownBy(() -> policy.advance(legacy, 1, 6, ANSWER,
                decision(Action.ADVANCE, legacy.plan().getLast().id(), Probe.OPENING))).hasMessageContaining("nextCompetencyId");
    }

    @Test void optInAppliesOnlyToNewTeachingSessionsAndDoesNotExpandTrainingScope() {
        var users = org.mockito.Mockito.mock(cn.edu.whu.zhizhida.auth.api.CurrentUserProvider.class);
        org.mockito.Mockito.when(users.requireUserId()).thenReturn(7L);
        var sessions = org.mockito.Mockito.mock(InterviewSessionRepository.class);
        var resumes = org.mockito.Mockito.mock(cn.edu.whu.zhizhida.resume.api.ResumeSnapshotReader.class);
        var ai = new MockInterviewAiService(json);
        var service = InterviewMachineFixture.machine(sessions, users, cn.edu.whu.zhizhida.ai.api.AiMode.MOCK, resumes);
        var request = new InterviewStartRequest("岗位", null, null, null, InterviewMode.GENERAL, 6, null, null, null);
        assertThat(service.prepareStart(request).agent().version()).isEqualTo("interview-agent-v3");
        service = InterviewMachineFixture.machine(sessions, users, cn.edu.whu.zhizhida.ai.api.AiMode.MOCK, resumes, true);
        assertThat(service.prepareStart(request).agent().version()).isEqualTo(InterviewAgentPolicy.ADAPTIVE_VERSION);
        var formal = new InterviewStartRequest("岗位", null, null, null, InterviewMode.GENERAL, 6, null, null, null,
                null, null, null, null, null, null, InterviewExperience.Format.FORMAL, null);
        assertThat(service.prepareStart(formal).agent().version()).isEqualTo("interview-agent-v3");
        var training = new InterviewStartRequest("岗位", null, null, null, InterviewMode.GENERAL, 6, null, null, null,
                InterviewTraining.Topic.PROJECT, null, null);
        var trainingAgent = service.prepareStart(training).agent();
        assertThat(trainingAgent.version()).isEqualTo(InterviewAgentPolicy.ADAPTIVE_VERSION);
        assertThat(trainingAgent.training().topic()).isEqualTo(InterviewTraining.Topic.PROJECT);
        assertThat(trainingAgent.plan()).extracting(Competency::id).containsExactly("ownership", "tradeoffs");
    }
}
