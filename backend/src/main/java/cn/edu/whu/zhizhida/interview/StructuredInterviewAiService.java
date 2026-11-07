package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.interview.api.InterviewAiService.InterviewAiReply;
import cn.edu.whu.zhizhida.interview.api.InterviewSession.InterviewTurn;

import cn.edu.whu.zhizhida.interview.api.*;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import tools.jackson.databind.JsonNode;
import java.util.List;

/** 真实与模拟模型共用的结构化输出校验；字段或业务内容不合法时交由应用层决定有限修正。 */
public abstract class StructuredInterviewAiService implements InterviewAiService {
    private final BusinessMetrics metrics;
    private final InterviewOutputCodec codec = new InterviewOutputCodec();

    protected StructuredInterviewAiService(BusinessMetrics metrics) { this.metrics = metrics; }

    @Override
    public final boolean supportsAgentDecisions() { return true; }

    protected abstract JsonNode generateOpening(String targetRole, String resumeSnapshot);
    protected abstract JsonNode generateReview(String targetRole, String resumeSnapshot, List<InterviewTurn> transcript,
                                              int answeredCount, boolean finalRound);

    @Override
    public final String openingQuestion(String targetRole, String resumeSnapshot) {
        return metrics.measure(BusinessMetrics.Operation.INTERVIEW_OPENING,
                () -> {
                    try { return parseOpening(generateOpening(targetRole, resumeSnapshot), resumeSnapshot); }
                    catch (cn.edu.whu.zhizhida.common.InvalidModelOutputException ex) { throw new InterviewReviewRejected(ex.getMessage()); }
                });
    }

    public String parseOpening(JsonNode payload, String context) {
        return codec.parseOpening(payload, context);
    }

    @Override
    public final InterviewAiReply reviewAnswer(String targetRole, String resumeSnapshot, List<InterviewTurn> transcript,
                                              int answeredCount, boolean finalRound) {
        return metrics.measure(BusinessMetrics.Operation.INTERVIEW_REVIEW, () -> {
            JsonNode payload;
            try { payload = generateReview(targetRole, resumeSnapshot, transcript, answeredCount, finalRound); }
            catch (cn.edu.whu.zhizhida.common.InvalidModelOutputException ex) { throw new InterviewReviewRejected(ex.getMessage()); }
            return codec.parseReview(payload, resumeSnapshot, finalRound, transcript);
        });
    }

    public InterviewAiReply parseReview(JsonNode payload, String context, boolean finalRound) {
        return codec.parseReview(payload, context, finalRound);
    }

    protected static String normalizeNullable(String value) { return value == null ? "" : value.trim(); }
}
