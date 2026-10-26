package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.interview.api.*;
import cn.edu.whu.zhizhida.interview.api.InterviewAgentState.*;
import cn.edu.whu.zhizhida.interview.domain.InterviewAgentPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

class InterviewAgentPolicyV2Test {
    private final InterviewAgentPolicy policy = new InterviewAgentPolicy();
    private static final String ANSWER = "我负责接口设计并进行了对照验证";

    @ParameterizedTest @EnumSource(InterviewMode.class)
    void everyDurationCoversItsPlanWhetherFollowUpsAreSpentEarlyOrLate(InterviewMode mode) {
        for (int rounds = 1; rounds <= 12; rounds++) {
            for (boolean early : List.of(true, false)) {
                var state = policy.plan(mode, rounds, "", "model");
                int size = state.plan().size();
                assertThat(size).isEqualTo(Math.min(rounds, Math.min(8, (2 * rounds + 2) / 3 + 1)));
                assertThat(policy.routing(state, 0, rounds).followUpBudget()).isEqualTo(rounds - size);
                for (int answered = 1; answered <= rounds; answered++) {
                    var route = policy.routing(state, answered, rounds);
                    var action = route.allowedActions().contains(Action.FINISH) ? Action.FINISH
                            : early && route.allowedActions().contains(Action.FOLLOW_UP) ? Action.FOLLOW_UP
                            : route.allowedActions().contains(Action.ADVANCE) ? Action.ADVANCE : Action.FOLLOW_UP;
                    if (route.followUpBudget() == 0 && answered < rounds) assertThat(action).isEqualTo(Action.ADVANCE);
                    state = policy.advance(state, answered, rounds, ANSWER, decision(route, action));
                }
                assertThat(state.evaluations()).hasSize(rounds);
                assertThat(state.evaluations().stream().map(Evaluation::competencyId).distinct()).hasSize(size);
                assertThat(state.evaluations().stream().filter(e -> e.decision().action() == Action.FOLLOW_UP)).hasSize(rounds - size);
                if (rounds == 12 && early) {
                    String firstId = state.plan().get(0).id();
                    assertThat(state.evaluations().subList(0, 3)).allMatch(e -> e.competencyId().equals(firstId));
                    assertThat(state.evaluations().subList(0, 2).stream().map(e -> e.decision().probe()).distinct()).hasSize(2);
                }
                assertThat(state.evaluations().stream().collect(java.util.stream.Collectors.groupingBy(Evaluation::competencyId)))
                        .allSatisfy((id, evaluations) -> assertThat(evaluations).hasSizeLessThanOrEqualTo(3));
            }
        }
    }

    @Test void resumeProjectsRolesAndContributionsChangePrioritiesAndQuestionAnchors() {
        var projects = List.of(new InterviewPlanningContext.Project("库存服务", "后端负责人", "设计事务和数据库索引", "PostgreSQL"),
                new InterviewPlanningContext.Project("分析平台", "性能开发", "测量延迟并完成压测优化", "ClickHouse"));
        var storage = policy.plan(InterviewMode.BACKEND_BASICS, 3, new InterviewPlanningContext(
                "负责数据库索引与事务设计", projects, List.of(), List.of("PostgreSQL"), ""), "model");
        assertThat(storage.plan().get(1).id()).isEqualTo("storage");
        assertThat(storage.plan().get(1).materialAnchors().toString()).contains("库存服务", "后端负责人", "设计事务和数据库索引");
        var performance = policy.plan(InterviewMode.BACKEND_BASICS, 3, new InterviewPlanningContext(
                "负责性能压测、吞吐与延迟优化", projects, List.of(), List.of("ClickHouse"), ""), "model");
        assertThat(performance.plan().get(1).id()).isEqualTo("performance");
        assertThat(performance.plan().get(1).materialAnchors().toString()).contains("分析平台", "性能开发", "测量延迟");
        var general = policy.plan(InterviewMode.GENERAL, 12, new InterviewPlanningContext("使用 GraphQL 推进团队协作", projects,
                List.of("某团队实习，负责需求沟通与交付"), List.of("GraphQL"), ""), "model");
        assertThat(general.plan().stream().filter(c -> c.id().equals("collaboration")).findFirst().orElseThrow().materialAnchors().toString()).contains("负责需求沟通与交付");
        assertThat(general.plan().stream().filter(c -> c.id().equals("expertise")).findFirst().orElseThrow().materialAnchors().toString()).contains("GraphQL");
        var noMaterials = policy.plan(InterviewMode.GENERAL, 3, "", "model");
        assertThat(noMaterials.plan()).allMatch(c -> c.materialAnchors().isEmpty());
    }

    @Test void attributesAssessmentToTheQuestionActuallyAnsweredAndRejectsRepeatedProbe() {
        var start = policy.plan(InterviewMode.GENERAL, 12, "", "model");
        var first = decision(policy.routing(start, 1, 12), Action.FOLLOW_UP);
        var state = policy.advance(start, 1, 12, ANSWER, first);
        assertThat(state.evaluations().get(0).answeredProbe()).isEqualTo(Probe.OPENING);
        assertThat(policy.routing(state, 2, 12).allowedProbes()).doesNotContain(first.probe());
        assertThatThrownBy(() -> policy.advance(state, 2, 12, ANSWER, first)).hasMessageContaining("重复策略");
        var second = decision(policy.routing(state, 2, 12), Action.FOLLOW_UP);
        var updated = policy.advance(state, 2, 12, ANSWER, second);
        assertThat(updated.evaluations().get(1).answeredProbe()).isEqualTo(first.probe());
        assertThat(updated.evaluations().get(1).answeredIntent()).isEqualTo(first.intent());
        assertThat(updated.through(1).evaluations()).hasSize(1);
        var invalid = new Decision(Level.PARTIAL, ANSWER, "缺口", Action.FOLLOW_UP, state.activeCompetencyId(), "原因", "训练", second.probe(), "意".repeat(201), state.activeCompetencyId());
        assertThatThrownBy(() -> policy.advance(state, 2, 12, ANSWER, invalid)).hasMessageContaining("200");
    }

    @Test void legacyCheckpointReadsWithoutInventingProbeHistory() throws Exception {
        var json = """
                {"version":"interview-agent-v1","executionMode":"model","plan":[{"id":"experience","name":"经历","objective":"职责"}],
                 "activeCompetencyId":"experience","evaluations":[{"round":1,"competencyId":"experience",
                 "decision":{"level":"PARTIAL","evidence":"个人行动","gap":"缺口","action":"FOLLOW_UP","nextCompetencyId":"experience","reason":"原因","practice":"训练"}}]}
                """;
        var state = new ObjectMapper().readValue(json, InterviewAgentState.class);
        assertThat(state.evaluations().get(0).answeredProbe()).isNull();
        assertThat(state.evaluations().get(0).decision().probe()).isNull();
        assertThat(state.through(1)).isEqualTo(state);
        assertThat(policy.routing(state, 2, 12).allowedProbes()).isNotEmpty();
    }

    private Decision decision(InterviewAgentPolicy.Routing route, Action action) {
        return new Decision(Level.PARTIAL, ANSWER, "尚缺验证", action,
                action == Action.ADVANCE ? route.next().id() : route.current().id(), "依据", "训练",
                action == Action.FOLLOW_UP ? route.allowedProbes().get(0) : action == Action.ADVANCE ? Probe.OPENING : Probe.NONE, "验证实际行动和结果",
                action == Action.FINISH ? "" : action == Action.ADVANCE ? route.next().id() : route.current().id());
    }
}
