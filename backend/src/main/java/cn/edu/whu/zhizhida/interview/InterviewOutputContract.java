package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.evaluation.api.AssessmentResult;
import cn.edu.whu.zhizhida.evaluation.api.DetailedAssessment;
import cn.edu.whu.zhizhida.evaluation.api.DetailedAssessmentDraft;
import cn.edu.whu.zhizhida.interview.api.InterviewAgentState;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 领域字段清单及本轮生成契约；服务端派生字段不参与生成，不是供应商的 JSON Schema 请求参数。
 * 长度和引用均以 UTF-16 单位描述，领域间关系仍由策略和证据校验器验证。
 */
public final class InterviewOutputContract {
    public static final String VERSION = "interview-output-v4";
    public static final int QUESTION_MAX = 300;
    public static final int FEEDBACK_MAX = 1000;
    public static final int NEXT_QUESTION_MAX = 600;
    public static final int SUMMARY_MAX = 2000;
    public static final int ID_MAX = 80;
    public static final int EVIDENCE_MAX = 500;
    public static final int DECISION_TEXT_MAX = 300;
    public static final int INTENT_MAX = 200;
    private static final JsonMapper JSON = new JsonMapper();

    private InterviewOutputContract() { }

    public static String prompt(boolean opening, boolean agent, boolean finalRound) {
        return prompt(opening, agent, finalRound, false);
    }

    public static String prompt(boolean opening, boolean agent, boolean finalRound, boolean planning) {
        var source = contract(opening, agent, finalRound, planning).deepCopy();
        // 本轮生成只要求新版 detail；旧 items 的兼容契约仍保留在完整清单中。
        if (!opening) {
            var assessmentFields = (ObjectNode) source.path("fields").path("assessment").path("fields");
            assessmentFields.remove("items");
            ((ObjectNode) assessmentFields.path("detail")).put("type", "required object when assessment is supplied");
        }
        if (!agent) ((ObjectNode) source.path("fields")).remove("assessment");
        var fields = (ObjectNode) source.path("fields");
        if (!opening) {
            fields.remove(finalRound ? "nextQuestion" : "finalSummary");
            if (agent) {
                var decision = (ObjectNode) fields.path("agentDecision").path("fields");
                decision.remove("questionCompetencyId");
                if (finalRound) decision.remove(java.util.List.of("action", "nextCompetencyId", "probe"));
            }
        }
        return "\n服务端字段契约（硬上限；前文较短长度是生成建议）。仅输出该轮字段，未知字段不要生成：\n"
                + compact(source.path("fields"));
    }

    private static JsonNode compact(JsonNode source) {
        if (source.isTextual()) {
            String value = source.textValue();
            if (value.startsWith("string;") && value.contains("; maxUTF16=")) {
                var parts = value.split(";", 4);
                String maximum = parts[2].trim().replace("maxUTF16=", "");
                String shape = parts[1].contains("nonblank")
                        ? "nonblank string≤" + maximum + " UTF16" : "string[0.." + maximum + " UTF16]";
                return JSON.getNodeFactory().stringNode(shape + (parts.length == 4 ? ";" + parts[3] : ""));
            }
            return JSON.getNodeFactory().stringNode(value);
        }
        var result = JSON.createObjectNode();
        if (source.has("type")) result.set("$type", compact(source.path("type")));
        if (source.has("itemFields")) {
            result.set("items", compact(source.path("itemFields")));
        } else {
            var fields = source.has("fields") ? source.path("fields") : source;
            fields.properties().forEach(entry -> result.set(entry.getKey(), compact(entry.getValue())));
        }
        return result;
    }

    public static JsonNode contract(boolean opening, boolean agent, boolean finalRound) {
        return contract(opening, agent, finalRound, false);
    }

    public static JsonNode contract(boolean opening, boolean agent, boolean finalRound, boolean planning) {
        var result = JSON.createObjectNode().put("version", VERSION).put("lengthUnit", "UTF16")
                .put("mode", opening ? "OPENING" : finalRound ? "FINAL_REVIEW" : "REVIEW")
                .put("unknownRootFields", "ignored by parser; do not generate")
                .put("textNormalization", "trim top-level and decision text; evidence and citations preserve raw text");
        var fields = result.putObject("fields");
        if (opening) {
            fields.put("question", text(QUESTION_MAX, false));
            if (agent) fields.put("questionCompetencyId", text(ID_MAX, false) + "; equals current.id");
            return result;
        }
        fields.put("feedback", text(FEEDBACK_MAX, false));
        fields.put("nextQuestion", finalRound ? "empty string; ignored by parser" : text(NEXT_QUESTION_MAX, false));
        fields.put("finalSummary", finalRound ? text(SUMMARY_MAX, false) : "empty string; ignored by parser");
        if (agent) fields.set("agentDecision", decision(planning));
        fields.set("assessment", assessment());
        result.put("semantics", "Structure alone does not validate evidence. Server validates allowed actions/probes, coverage, "
                + "citations against current owner and fixed source versions, RAW_UTF16 offsets, observation consistency and revocation. "
                + "Missing assessment remains NOT_EVALUATED for compatibility; generation should include detailed assessment.");
        return result;
    }

    private static ObjectNode decision(boolean planning) {
        var node = JSON.createObjectNode().put("type", "object").put("required", "all fields")
                .put("unknownFields", "ignored by parser; do not generate");
        var fields = node.putObject("fields");
        fields.put("level", values(InterviewAgentState.Level.class));
        fields.put("evidence", text(EVIDENCE_MAX, true) + "; one contiguous verbatim substring of the latest answer; no omissions or concatenation; empty only for INSUFFICIENT");
        fields.put("gap", text(DECISION_TEXT_MAX, false));
        fields.put("action", values(InterviewAgentState.Action.class) + "; restricted to allowedActions");
        fields.put("nextCompetencyId", text(ID_MAX, false) + "; target allowed by agentRouting");
        fields.put("reason", text(DECISION_TEXT_MAX, false));
        fields.put("practice", text(DECISION_TEXT_MAX, false));
        fields.put("probe", values(InterviewAgentState.Probe.class) + "; FOLLOW_UP=allowedProbes; ADVANCE=OPENING; FINISH=NONE");
        fields.put("intent", text(INTENT_MAX, false));
        fields.put("questionCompetencyId", text(ID_MAX, true) + "; equals nextCompetencyId except FINISH=empty");
        if (planning) {
            var focus = fields.putObject("focus").put("type", "required object").putObject("fields");
            focus.put("dimension", values(cn.edu.whu.zhizhida.interview.api.InterviewPlanning.Dimension.class));
            focus.put("scenario", values(cn.edu.whu.zhizhida.interview.api.InterviewPlanning.Scenario.class));
            focus.put("claimToVerify", text(INTENT_MAX, false));
            focus.put("expectedEvidence", text(INTENT_MAX, false));
        }
        return node;
    }

    private static ObjectNode assessment() {
        var node = JSON.createObjectNode().put("type", "optional object or null").put("unknownFields", "rejected")
                .put("generation", "use detail; legacy items optional for old outputs; absent means not evaluated")
                .put("presence", "A non-null assessment must supply valid legacy items or non-null detail; empty assessment is rejected by domain validation. "
                        + "If both are supplied, both must validate. Missing/null assessment remains not evaluated.");
        var fields = node.putObject("fields");
        var legacy = fields.putObject("items").put("type", "optional array; exactly 2 distinct dimensions if supplied");
        var oldItem = legacy.putObject("itemFields");
        observation(oldItem, AssessmentResult.Dimension.class);
        oldItem.putObject("quotes").put("type", "array; 0..2; nonempty only for OBSERVED")
                .set("itemFields", quote(false));
        var detail = fields.putObject("detail").put("type", "optional object").put("unknownFields", "rejected");
        var detailFields = detail.putObject("fields");
        detailFields.put("protocolVersion", "literal six-dimensions-v1");
        var items = detailFields.putObject("items").put("type", "array; exactly 6 distinct dimensions; unknown item fields rejected");
        var item = items.putObject("itemFields");
        item.putObject("citations").put("type", "array; 0..3; OBSERVED requires answer citation and non-INSUFFICIENT; otherwise empty and INSUFFICIENT")
                .set("itemFields", quote(true));
        observation(item, DetailedAssessment.Dimension.class);
        item.put("majorCorrection", "boolean; true only for OBSERVED TECHNICAL_CORRECTNESS; omitted defaults false");
        node.put("relations", "OBSERVED TECHNICAL_CORRECTNESS additionally requires a fixed reference citation; "
                + "CONTRADICTED requires majorCorrection; answer=SELF_REPORT, reference=SUPPORT/COUNTEREVIDENCE; conflicts remain DISPUTED. "
                + "Legacy items cannot claim CONTRADICTED; OBSERVED requires quotes and non-INSUFFICIENT, otherwise empty quotes and INSUFFICIENT.");
        return node;
    }

    private static <T extends Enum<T>> void observation(ObjectNode fields, Class<T> dimensions) {
        fields.put("dimension", values(dimensions));
        fields.put("status", values(AssessmentResult.Observation.class));
        fields.put("claimText", text(300, false));
        fields.put("claimStatus", values(AssessmentResult.ClaimStatus.class));
        fields.put("limitation", text(300, false));
    }

    private static ObjectNode quote(boolean detailed) {
        var fields = JSON.createObjectNode();
        if (detailed) fields.put("sourceKey", "string; answer or assessmentSources sourceKey only");
        fields.put("anchorId", "string; citationAnchors ID for sourceKey; unique exact quote; no offsets");
        fields.put("quote", text(EVIDENCE_MAX, false) + "; exact source substring without normalization");
        if (detailed) fields.put("relation", values(DetailedAssessmentDraft.Relation.class));
        return fields;
    }

    private static String text(int maximum, boolean empty) {
        return "string; " + (empty ? "empty allowed" : "nonblank") + "; maxUTF16=" + maximum;
    }

    private static <T extends Enum<T>> String values(Class<T> type) {
        return java.util.Arrays.toString(type.getEnumConstants());
    }
}
