package cn.edu.whu.zhizhida.common;

import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 对模型返回的非可信 JSON 执行字段类型、长度和集合大小校验。
 * 不将数字或对象隐式转为字符串，避免格式错误的响应被当成业务成功。
 */
public final class StructuredOutput {
    public enum RejectionReason { MISSING_FIELD, TYPE_MISMATCH, EMPTY_VALUE, LIMIT_EXCEEDED, INVALID_VALUE,
        INVALID_JSON, SEMANTIC_MISMATCH }
    // 日志只允许服务端协议字段；未知或动态字段一律归并，避免把模型键名写入诊断日志。
    private static final Set<String> DIAGNOSTIC_FIELDS = Set.of("$", "question", "questionCompetencyId",
            "feedback", "finalSummary", "nextQuestion", "assessment", "agentDecision", "agentDecision.evidence",
            "agentDecision.questionCompetencyId", "agentDecision.level", "agentDecision.action", "agentDecision.probe", "agentDecision.focus",
            "agentDecision.gap", "agentDecision.nextCompetencyId", "agentDecision.reason", "agentDecision.practice", "agentDecision.intent",
            "level", "action", "probe", "gap", "nextCompetencyId", "reason", "practice", "intent",
            "agentInputBudget", "agentContext", "version", "phase", "result",
            "knowledgeQuestions", "knowledgeQuestion", "knowledgeQuestionSource", "knowledgeQuestionQuote", "knowledgeQuestionText",
            "ownerName", "targetRole", "summary", "skills", "educationExperiences", "projectExperiences",
            "internshipExperiences", "certificates", "details", "education", "school", "major", "degree", "startDate", "endDate",
            "projects", "name", "role", "period", "background", "contributions", "techStack", "graduationDate", "availability",
            "contact", "phone", "email", "location", "links", "url", "label");
    private StructuredOutput() { }

    public static String text(JsonNode payload, String field, int maxLength) {
        JsonNode node = payload == null ? null : payload.get(field);
        if (node == null || node.isNull()) throw invalid(field, RejectionReason.MISSING_FIELD);
        if (!node.isString()) throw invalid(field, RejectionReason.TYPE_MISMATCH);
        String value = node.stringValue().trim();
        if (value.isBlank()) throw invalid(field, RejectionReason.EMPTY_VALUE);
        if (value.length() > maxLength) throw invalid(field, RejectionReason.LIMIT_EXCEEDED);
        return value;
    }

    public static JsonNode array(JsonNode payload, String field, int maxSize) {
        JsonNode node = payload == null ? null : payload.get(field);
        if (node == null || node.isNull()) throw invalid(field, RejectionReason.MISSING_FIELD);
        if (!node.isArray()) throw invalid(field, RejectionReason.TYPE_MISMATCH);
        if (node.size() > maxSize) throw invalid(field, RejectionReason.LIMIT_EXCEEDED);
        return node;
    }

    public static List<String> texts(JsonNode payload, String field, int maxSize, int maxLength) {
        List<String> result = new ArrayList<>();
        for (JsonNode node : array(payload, field, maxSize)) {
            if (!node.isString()) throw invalid(field, RejectionReason.TYPE_MISMATCH);
            if (node.stringValue().isBlank()) throw invalid(field, RejectionReason.EMPTY_VALUE);
            if (node.stringValue().trim().length() > maxLength) throw invalid(field, RejectionReason.LIMIT_EXCEEDED);
            result.add(node.stringValue().trim());
        }
        return List.copyOf(result);
    }

    public static FieldValidationException invalid(String field) {
        return invalid(field, RejectionReason.INVALID_VALUE);
    }

    public static FieldValidationException invalid(String field, RejectionReason reason) {
        return new FieldValidationException(field, reason);
    }

    public static String diagnosticField(String field) {
        return field != null && DIAGNOSTIC_FIELDS.contains(field) ? field : "$";
    }

    public static final class FieldValidationException extends UpstreamServiceException {
        private final RejectionReason reason;
        private final String fieldPath;

        private FieldValidationException(String field, RejectionReason reason) {
            super("模型业务响应字段 " + field + " 缺失或格式、长度不合法，请重试。");
            this.reason = reason;
            this.fieldPath = diagnosticField(field);
        }

        public RejectionReason reason() { return reason; }
        public String fieldPath() { return fieldPath; }
    }
}
