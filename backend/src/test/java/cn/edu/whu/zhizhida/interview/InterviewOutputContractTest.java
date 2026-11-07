package cn.edu.whu.zhizhida.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import cn.edu.whu.zhizhida.common.StructuredOutput;
import cn.edu.whu.zhizhida.evaluation.EvaluationEngine;
import cn.edu.whu.zhizhida.evaluation.api.AssessmentDraft;
import cn.edu.whu.zhizhida.evaluation.api.DetailedAssessmentDraft;
import cn.edu.whu.zhizhida.interview.api.InterviewAgentState;
import cn.edu.whu.zhizhida.interview.api.InterviewMode;
import cn.edu.whu.zhizhida.interview.api.InterviewSession.InterviewTurn;
import cn.edu.whu.zhizhida.interview.domain.InterviewAgentPolicy;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class InterviewOutputContractTest {
    private final JsonMapper json = new JsonMapper();
    private final DefaultInterviewAiService service = new DefaultInterviewAiService(org.mockito.Mockito.mock(cn.edu.whu.zhizhida.ai.api.JsonChatClient.class), json, BusinessMetrics.disabled());

    @Test
    void everyDeclaredNestedRecordFieldHasAContractWithoutRetiredPlanning() {
        var fields = InterviewOutputContract.contract(false, true, false).path("fields");
        sameFields(fields.path("agentDecision").path("fields"), InterviewAgentState.Decision.class);
        var assessment = fields.path("assessment").path("fields");
        sameFields(assessment, AssessmentDraft.class);
        sameFields(assessment.path("items").path("itemFields"), AssessmentDraft.Item.class);
        sameFields(assessment.path("items").path("itemFields").path("quotes").path("itemFields"), AssessmentDraft.Quote.class);
        var detail = assessment.path("detail").path("fields");
        sameFields(detail, DetailedAssessmentDraft.class);
        sameFields(detail.path("items").path("itemFields"), DetailedAssessmentDraft.Item.class);
        sameFields(detail.path("items").path("itemFields").path("citations").path("itemFields"), DetailedAssessmentDraft.Citation.class);
        assertThat(InterviewOutputContract.contract(false, true, false).path("fields").has("planProposal")).isFalse();
        assertThat(InterviewOutputContract.contract(false, true, true).path("fields").has("planProposal")).isFalse();
        assertThat(InterviewOutputContract.contract(true, false, false).path("fields").has("questionCompetencyId")).isFalse();
        assertThat(InterviewOutputContract.contract(true, true, false).path("fields").has("questionCompetencyId")).isTrue();
        var renderedVariants = service.templateManifest().path("renderedContracts");
        assertThat(renderedVariants.size()).isEqualTo(6);
        for (var variant : renderedVariants) {
            String prompt = variant.asText();
            assertThat(json.readTree(prompt.substring(prompt.lastIndexOf('\n') + 1)).isObject()).isTrue();
        }
    }

    @Test
    void actualPromptUsesDeclaredContractAndFitsExistingAdapterLimitIncludingRepair() {
        var policy = new InterviewAgentPolicy();
        var state = policy.plan(InterviewMode.GENERAL, 6, "", "model");
        var v4 = new InterviewAgentState("interview-agent-v4", state.executionMode(), state.plan(), state.activeCompetencyId(), List.of());
        var context = InterviewAgentContext.encode("private material", v4, policy.routing(v4, 1, 6));
        var reason = new InterviewReviewRejected(StructuredOutput.invalid("agentDecision.gap", StructuredOutput.RejectionReason.MISSING_FIELD));
        var input = service.reviewInput("Java", context, List.of(new InterviewTurn("user", "private answer")), 1, false, reason.repairFeedback());
        assertThat(input.system()).contains(InterviewOutputContract.prompt(false, true, false));
        assertThat(input.system().length()).isLessThanOrEqualTo(7500);
        assertThat(json.readTree(input.user()).path("validationFeedback").path("path").asText()).isEqualTo("agentDecision.gap");
        assertThat(service.templateManifest().toString()).doesNotContain("private material", "private answer");
        assertThat(service.templateManifest().path("renderedContracts").toString()).doesNotContain("planProposal");
        assertThat(input.user()).doesNotContain("plannerVersion", "candidateDifficulties", "previousIntents");
        assertThat(input.temperature()).isEqualTo(service.templateManifest().path("generation").path("reviewTemperature").asDouble());
        assertThat(input.maxTokens()).isEqualTo(service.templateManifest().path("generation").path("agentReviewMaxTokens").asInt());
        assertThat(service.openingInput("Java", context, reason.repairFeedback()).system().length()).isLessThanOrEqualTo(8000);
        var finalInput = service.reviewInput("Java", context, List.of(new InterviewTurn("user", "private answer")), 6, true, null);
        assertThat(finalInput.system()).doesNotContain("planProposal", "本场启用 intent-planner-v4.1");
        assertThat(finalInput.system()).contains(InterviewOutputContract.prompt(false, true, true));
        assertThat(input.system()).contains("required object when assessment is supplied");
    }

    @Test
    void hardLimitsUseUtf16AndRetainLegacyUnknownFieldAndMissingAssessmentCompatibility() {
        var opening = json.createObjectNode().put("question", "😀".repeat(150)).put("ignored", "legacy extension");
        assertThat(service.parseOpening(opening, "").length()).isEqualTo(InterviewOutputContract.QUESTION_MAX);
        opening.put("question", "😀".repeat(150) + "a");
        assertThatThrownBy(() -> service.parseOpening(opening, "")).isInstanceOfSatisfying(InterviewReviewRejected.class,
                ex -> assertThat(ex.reason()).isEqualTo(StructuredOutput.RejectionReason.LIMIT_EXCEEDED));
        var review = json.createObjectNode().put("feedback", "f".repeat(1000)).put("nextQuestion", "q".repeat(600)).put("ignored", true);
        assertThat(service.parseReview(review, "", false).assessment()).isNull();
        review.putObject("assessment").put("ownerId", 999);
        assertThatThrownBy(() -> service.parseReview(review, "", false)).isInstanceOf(InterviewReviewRejected.class);
        review.putObject("assessment");
        var emptyAssessment = service.parseReview(review, "", false).assessment();
        assertThatThrownBy(() -> new EvaluationEngine().evaluateAnswer(1, "fixture-answer", "回答", Instant.EPOCH,
                emptyAssessment, "GENERAL")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("assessment 必须包含两个独立维度");
    }

    @Test
    void diagnosticFeedbackNeverCopiesUntrustedExceptionText() {
        var rejected = new InterviewReviewRejected(StructuredOutput.invalid("private\nAuthorization: secret"));
        var feedback = json.readTree(rejected.repairFeedback());
        assertThat(feedback.path("path").asText()).isEqualTo("$");
        assertThat(feedback.path("code").asText()).isEqualTo("INVALID_VALUE");
        assertThat(feedback.toString()).doesNotContain("private", "Authorization", "secret");
        var semantic = new InterviewReviewRejected("观察与证据状态不一致");
        assertThat(json.readTree(semantic.repairFeedback()).path("constraint").asText()).contains("OBSERVED", "INSUFFICIENT");
    }

    @Test
    void retainedHistoricalCandidateStillFailsSemanticValidationEvenThoughStructureParses() throws Exception {
        JsonNode fixture;
        try (var input = getClass().getResourceAsStream("/interview-output-replay.json")) { fixture = json.readTree(input); }
        var reply = service.parseReview(fixture.path("candidate"), "", false);
        assertThat(reply.assessment().detail()).isNotNull();
        assertThatThrownBy(() -> new EvaluationEngine().evaluateAnswer(1, "fixture-answer", fixture.path("answer").asText(),
                Instant.parse("2026-10-31T00:00:00Z"), reply.assessment(), "GENERAL"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(fixture.path("expectedRejection").asText());
    }

    private static void sameFields(JsonNode declared, Class<?> record) {
        var expected = new java.util.ArrayList<>(Arrays.stream(record.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList());
        if (expected.remove("offsetStart")) {
            expected.remove("offsetEnd");
            expected.add("anchorId");
        }
        assertThat(declared.propertyNames()).containsExactlyInAnyOrderElementsOf(
                expected);
    }
}
