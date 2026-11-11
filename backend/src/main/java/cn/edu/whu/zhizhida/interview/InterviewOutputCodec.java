package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.interview.api.InterviewAiService.InterviewAiReply;
import cn.edu.whu.zhizhida.interview.api.InterviewSession.InterviewTurn;

import cn.edu.whu.zhizhida.interview.api.*;
import cn.edu.whu.zhizhida.common.StructuredOutput;
import tools.jackson.databind.JsonNode;
import java.util.List;

/** 仅解析模型输出，不依赖模型客户端、指标或业务服务。 */
public final class InterviewOutputCodec {
    private static final tools.jackson.databind.ObjectReader ASSESSMENT_READER = strictMapper()
            .readerFor(cn.edu.whu.zhizhida.evaluation.api.AssessmentDraft.class)
            .with(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .with(tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .without(tools.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    private static tools.jackson.databind.json.JsonMapper strictMapper() {
        return tools.jackson.databind.json.JsonMapper.builder()
                .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    public String parseOpening(JsonNode payload, String contextText) {
        var context = InterviewAgentContext.decode(contextText); // 本地状态错误不属于可交由模型修正的错误
        try {
            String question = StructuredOutput.text(payload, "question", InterviewOutputContract.QUESTION_MAX);
            if (context != null && context.checkpoint() != null) {
                String expected = context.routing().current().id();
                if (!expected.equals(StructuredOutput.text(payload, "questionCompetencyId", InterviewOutputContract.ID_MAX)))
                    throw new InterviewReviewRejected("questionCompetencyId 必须为开场能力 " + expected);
                try { new cn.edu.whu.zhizhida.interview.domain.InterviewAgentPolicy().validateQuestion(expected, question); }
                catch (IllegalArgumentException ex) { throw new InterviewReviewRejected(ex.getMessage()); }
            }
            return question;
        } catch (StructuredOutput.FieldValidationException ex) { throw new InterviewReviewRejected(ex); }
    }

    public InterviewAiReply parseReview(JsonNode payload, String resumeSnapshot, boolean finalRound) {
        return parseReview(payload, resumeSnapshot, finalRound, List.of());
    }

    public InterviewAiReply parseReview(JsonNode payload, String resumeSnapshot, boolean finalRound, List<InterviewTurn> transcript) {
        return parseReviewWithAnchors(payload, resumeSnapshot, finalRound, InterviewCitationAnchors.catalog(resumeSnapshot, transcript));
    }

    InterviewAiReply parseReviewWithAnchors(JsonNode payload, String resumeSnapshot, boolean finalRound, List<InterviewCitationAnchors.Anchor> anchors) {
        payload = InterviewCitationAnchors.resolve(payload, anchors);
        var context = InterviewAgentContext.decode(resumeSnapshot);
        try {
            String feedback = StructuredOutput.text(payload, "feedback", InterviewOutputContract.FEEDBACK_MAX).replaceFirst("^即时反馈[:：]\\s*", "");
            if (feedback.isBlank()) throw StructuredOutput.invalid("feedback");
            var decision = context == null || context.checkpoint() == null ? null : parseDecision(payload, context, finalRound);
            // 轮次由应用规则决定，模型文本不能直接结束面试状态机。
            return finalRound
                    ? new InterviewAiReply(feedback, "", StructuredOutput.text(payload, "finalSummary", InterviewOutputContract.SUMMARY_MAX), decision, parseAssessment(payload))
                    : new InterviewAiReply(feedback, StructuredOutput.text(payload, "nextQuestion", InterviewOutputContract.NEXT_QUESTION_MAX), "", decision, parseAssessment(payload));
        } catch (StructuredOutput.FieldValidationException ex) { throw new InterviewReviewRejected(ex); }
    }

    private cn.edu.whu.zhizhida.evaluation.api.AssessmentDraft parseAssessment(JsonNode payload) {
        var node = payload.path("assessment");
        if (node.isMissingNode() || node.isNull()) return null; // 旧版输出保持未评估状态
        try {
            if (!node.isObject()) throw new IllegalArgumentException();
            return ASSESSMENT_READER.readValue(node.toString());
        } catch (RuntimeException ex) { throw new InterviewReviewRejected("assessment 结构或枚举无效", StructuredOutput.RejectionReason.TYPE_MISMATCH, "assessment"); }
    }

    private InterviewAgentState.Decision parseDecision(JsonNode payload, InterviewAgentContext context, boolean finalRound) {
        JsonNode node = payload.path("agentDecision");
        if (node.isMissingNode() || node.isNull())
            throw StructuredOutput.invalid("agentDecision", StructuredOutput.RejectionReason.MISSING_FIELD);
        if (!node.isObject()) throw StructuredOutput.invalid("agentDecision", StructuredOutput.RejectionReason.TYPE_MISMATCH);
        var action = finalRound ? InterviewAgentState.Action.FINISH : enumField(InterviewAgentState.Action.class, node, "action");
        String target = finalRound ? context.routing().current().id() : decisionText(node, "nextCompetencyId", InterviewOutputContract.ID_MAX);
        var probe = finalRound ? InterviewAgentState.Probe.NONE : enumField(InterviewAgentState.Probe.class, node, "probe");
        return new InterviewAgentState.Decision(
                enumField(InterviewAgentState.Level.class, node, "level"),
                optionalEmptyText(node, "evidence", InterviewOutputContract.EVIDENCE_MAX),
                decisionText(node, "gap", InterviewOutputContract.DECISION_TEXT_MAX),
                action,
                target,
                decisionText(node, "reason", InterviewOutputContract.DECISION_TEXT_MAX),
                decisionText(node, "practice", InterviewOutputContract.DECISION_TEXT_MAX),
                probe,
                decisionText(node, "intent", InterviewOutputContract.INTENT_MAX),
                action == InterviewAgentState.Action.FINISH ? "" : target);
    }

    private String decisionText(JsonNode node, String field, int max) {
        try { return StructuredOutput.text(node, field, max); }
        catch (StructuredOutput.FieldValidationException ex) {
            throw StructuredOutput.invalid("agentDecision." + field, ex.reason());
        }
    }

    private String optionalEmptyText(JsonNode node, String field, int max) {
        var value = node.path(field);
        var path = "agentDecision." + field;
        if (value.isMissingNode() || value.isNull()) throw StructuredOutput.invalid(path, StructuredOutput.RejectionReason.MISSING_FIELD);
        if (!value.isTextual()) throw StructuredOutput.invalid(path, StructuredOutput.RejectionReason.TYPE_MISMATCH);
        if (value.textValue().length() > max) throw StructuredOutput.invalid(path, StructuredOutput.RejectionReason.LIMIT_EXCEEDED);
        return value.textValue();
    }

    private <T extends Enum<T>> T enumField(Class<T> type, JsonNode node, String field) {
        try { return Enum.valueOf(type, decisionText(node, field, 30)); }
        catch (IllegalArgumentException ex) { throw StructuredOutput.invalid("agentDecision." + field); }
    }

}
