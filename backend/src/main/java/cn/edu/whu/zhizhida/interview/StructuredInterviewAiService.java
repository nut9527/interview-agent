package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.interview.api.*;
import cn.edu.whu.zhizhida.common.StructuredOutput;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public abstract class StructuredInterviewAiService implements InterviewAiService {
    private final BusinessMetrics metrics;

    protected StructuredInterviewAiService(BusinessMetrics metrics) { this.metrics = metrics; }

    @Override
    public final boolean supportsAgentDecisions() { return true; }

    @Override
    public final boolean supportsReviewRepair() { return true; }
    @Override
    public final boolean supportsOpeningRepair() { return true; }

    protected abstract JsonNode generateOpening(String targetRole, String resumeSnapshot);
    protected abstract JsonNode generateReview(String targetRole, String resumeSnapshot, List<InterviewTurn> transcript,
                                              int answeredCount, boolean finalRound);

    protected JsonNode generateOpeningRepair(String role, String context, String reason) { return generateOpening(role, context); }

    protected JsonNode generateRepair(String targetRole, String context, List<InterviewTurn> transcript,
                                      int answeredCount, boolean finalRound, String reason) {
        return generateReview(targetRole, context, transcript, answeredCount, finalRound);
    }

    @Override
    public final String openingQuestion(String targetRole, String resumeSnapshot) {
        return metrics.measure(BusinessMetrics.Operation.INTERVIEW_OPENING,
                () -> {
                    try { return parseOpening(generateOpening(targetRole, resumeSnapshot), resumeSnapshot); }
                    catch (cn.edu.whu.zhizhida.common.InvalidModelOutputException ex) { throw new InterviewReviewRejected(ex.getMessage()); }
                });
    }

    @Override
    public final String repairOpening(String role, String context, String reason) {
        return parseOpening(generateOpeningRepair(role, context, reason), context);
    }

    private String parseOpening(JsonNode payload, String contextText) {
        var context = InterviewAgentContext.decode(contextText); // local state failures are never model-repair failures
        try {
            String question = StructuredOutput.text(payload, "question", 300);
            if (context != null && context.checkpoint() != null) {
                String expected = context.routing().current().id();
                if (!expected.equals(StructuredOutput.text(payload, "questionCompetencyId", 80)))
                    throw new InterviewReviewRejected("questionCompetencyId 必须为开场能力 " + expected);
                try { new cn.edu.whu.zhizhida.interview.domain.InterviewAgentPolicy().validateQuestion(expected, question); }
                catch (IllegalArgumentException ex) { throw new InterviewReviewRejected(ex.getMessage()); }
            }
            return question;
        } catch (StructuredOutput.FieldValidationException ex) { throw new InterviewReviewRejected(ex.getMessage()); }
    }

    @Override
    public final InterviewAiReply reviewAnswer(String targetRole, String resumeSnapshot, List<InterviewTurn> transcript,
                                              int answeredCount, boolean finalRound) {
        return metrics.measure(BusinessMetrics.Operation.INTERVIEW_REVIEW, () -> {
            JsonNode payload;
            try { payload = generateReview(targetRole, resumeSnapshot, transcript, answeredCount, finalRound); }
            catch (cn.edu.whu.zhizhida.common.InvalidModelOutputException ex) { throw new InterviewReviewRejected(ex.getMessage()); }
            return parseReview(payload, resumeSnapshot, finalRound);
        });
    }

    @Override
    public final InterviewAiReply repairAnswer(String role, String context, List<InterviewTurn> transcript,
                                               int answered, boolean finalRound, String reason) {
        return parseReview(generateRepair(role, context, transcript, answered, finalRound, reason), context, finalRound);
    }

    private InterviewAiReply parseReview(JsonNode payload, String resumeSnapshot, boolean finalRound) {
        var context = InterviewAgentContext.decode(resumeSnapshot);
        try {
            String feedback = StructuredOutput.text(payload, "feedback", 1000).replaceFirst("^即时反馈[:：]\\s*", "");
            if (feedback.isBlank()) throw StructuredOutput.invalid("feedback");
            var decision = context == null || context.checkpoint() == null ? null : parseDecision(payload);
            // 轮次由应用规则决定，模型文本不能直接结束面试状态机。
            return finalRound
                    ? new InterviewAiReply(feedback, "", StructuredOutput.text(payload, "finalSummary", 2000), decision)
                    : new InterviewAiReply(feedback, StructuredOutput.text(payload, "nextQuestion", 600), "", decision);
        } catch (StructuredOutput.FieldValidationException ex) { throw new InterviewReviewRejected(ex.getMessage()); }
    }

    private InterviewAgentState.Decision parseDecision(JsonNode payload) {
        JsonNode node = payload.path("agentDecision");
        try {
            if (!node.path("evidence").isTextual() || node.path("evidence").textValue().length() > 500)
                throw StructuredOutput.invalid("agentDecision.evidence");
            return new InterviewAgentState.Decision(
                    enumField(InterviewAgentState.Level.class, node, "level"),
                    node.path("evidence").textValue(), StructuredOutput.text(node, "gap", 300),
                    enumField(InterviewAgentState.Action.class, node, "action"),
                    StructuredOutput.text(node, "nextCompetencyId", 80),
                    StructuredOutput.text(node, "reason", 300), StructuredOutput.text(node, "practice", 300),
                    enumField(InterviewAgentState.Probe.class, node, "probe"),
                    StructuredOutput.text(node, "intent", 200), questionTarget(node));
        } catch (IllegalArgumentException ex) { throw StructuredOutput.invalid("agentDecision"); }
    }

    private String questionTarget(JsonNode node) {
        var target = node.path("questionCompetencyId");
        if (!target.isTextual() || target.textValue().length() > 80) throw StructuredOutput.invalid("agentDecision.questionCompetencyId");
        return target.textValue().trim();
    }

    private <T extends Enum<T>> T enumField(Class<T> type, JsonNode node, String field) {
        try { return Enum.valueOf(type, StructuredOutput.text(node, field, 30)); }
        catch (IllegalArgumentException ex) { throw StructuredOutput.invalid("agentDecision." + field); }
    }

    protected static String normalizeNullable(String value) { return value == null ? "" : value.trim(); }
}
