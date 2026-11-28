package cn.edu.whu.zhizhida.interview;

import static org.assertj.core.api.Assertions.*;

import cn.edu.whu.zhizhida.evaluation.EvaluationEngine;
import cn.edu.whu.zhizhida.interview.api.InterviewSession.InterviewTurn;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class ReleaseScenarioRegressionTest {
    @ParameterizedTest(name = "R2 {index}")
    @MethodSource("cn.edu.whu.zhizhida.interview.ReleaseScenarioFixtures#scenarios")
    void everyDevelopmentAndValidationScenarioUsesProductionProtocolAndRejectsSingleMutations(
            JsonNode fixture) {
        String context = ReleaseScenarioFixtures.context(fixture),
                answer = fixture.path("answer").asString();
        var transcript = List.of(new InterviewTurn("user", answer));
        var output = ReleaseScenarioFixtures.review(fixture, context);
        var codec = new InterviewOutputCodec();
        var reply = codec.parseReview(output, context, false, transcript);
        var references =
                InterviewAssessmentSources.references(
                        7, ReleaseScenarioFixtures.agent(fixture).training());
        var engine = new EvaluationEngine();
        var assessment =
                engine.evaluateAnswer(
                        7,
                        "r2-answer",
                        answer,
                        Instant.EPOCH,
                        reply.assessment(),
                        "GENERAL",
                        references);
        assertThat(assessment.detail().evidence()).hasSize(2);
        assertThat(assessment.detail().evidence())
                .anySatisfy(
                        e -> {
                            assertThat(e.quote()).isEqualTo(answer);
                            assertThat(e.offsetStart()).isZero();
                            assertThat(e.offsetEnd()).isEqualTo(answer.length());
                        });
        var policy = new cn.edu.whu.zhizhida.interview.domain.InterviewAgentPolicy();
        assertThat(
                        policy.advance(
                                        ReleaseScenarioFixtures.agent(fixture),
                                        1,
                                        6,
                                        answer,
                                        reply.agentDecision())
                                .evaluations())
                .hasSize(1);

        var wrongAnchor = output.deepCopy();
        ((ObjectNode)
                        wrongAnchor
                                .path("assessment")
                                .path("detail")
                                .path("items")
                                .get(1)
                                .path("citations")
                                .get(0))
                .put("anchorId", "wrong-version");
        assertThatThrownBy(() -> codec.parseReview(wrongAnchor, context, false, transcript))
                .hasMessageContaining("固定来源或版本");
        var conflict = output.deepCopy();
        ((ObjectNode) conflict.path("assessment").path("detail").path("items").get(1))
                .put("claimStatus", "INSUFFICIENT");
        var invalid = codec.parseReview(conflict, context, false, transcript);
        assertThatThrownBy(
                        () ->
                                engine.evaluateAnswer(
                                        7,
                                        "r2-answer",
                                        answer,
                                        Instant.EPOCH,
                                        invalid.assessment(),
                                        "GENERAL",
                                        references))
                .hasMessage("观察与证据状态不一致");
        var wrongAction = output.deepCopy();
        ((ObjectNode) wrongAction.path("agentDecision")).put("action", "FINISH");
        var forbidden = codec.parseReview(wrongAction, context, false, transcript);
        assertThatThrownBy(
                        () ->
                                policy.advance(
                                        ReleaseScenarioFixtures.agent(fixture),
                                        1,
                                        6,
                                        answer,
                                        forbidden.agentDecision()))
                .hasMessageContaining("allowedActions");
    }
}
