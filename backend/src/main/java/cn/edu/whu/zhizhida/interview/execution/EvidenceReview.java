package cn.edu.whu.zhizhida.interview.execution;

import cn.edu.whu.zhizhida.interview.InterviewReviewRejected;
import cn.edu.whu.zhizhida.interview.InterviewAssessmentSources;
import cn.edu.whu.zhizhida.interview.api.InterviewTraining;
import cn.edu.whu.zhizhida.interview.api.KnowledgeSnapshot;
import cn.edu.whu.zhizhida.evaluation.api.DetailedAssessment.Dimension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 模型判断支持关系，服务端核对来源并约束最终结论不能跳过未解决的检查。 */
final class EvidenceReview {
    private EvidenceReview() { }

    record Source(String text, boolean reference) { }

    static Map<String, Source> references(long owner, InterviewTraining training, List<KnowledgeSnapshot.Reference> retrieved) {
        var sources = new LinkedHashMap<String, Source>();
        for (var reference : InterviewAssessmentSources.references(owner, training, retrieved))
            sources.put(reference.key(), new Source(reference.text(), true));
        return sources;
    }

    static void validate(JsonNode action, Map<String, Source> sources, boolean opening) {
        var checks = action.path("checks");
        if (opening && checks.isMissingNode()) return;
        require(checks.isArray() && checks.size() <= 6 && (opening || !checks.isEmpty()), "checks 需要1至6项证据检查，开场可为空");
        var dimensions = new HashSet<String>();
        for (var check : checks) {
            require(check.isObject() && check.propertyNames().equals(Set.of("dimension", "claim", "status", "evidence", "nextAction")), "证据检查字段不完整");
            String dimension = check.path("dimension").asText();
            require(java.util.Arrays.stream(Dimension.values()).anyMatch(d -> d.name().equals(dimension)) && dimensions.add(dimension), "证据检查维度无效或重复");
            text(check, "claim", 240, false);
            text(check, "nextAction", 240, true);
            String status = check.path("status").asText();
            require(Set.of("SUPPORTED", "CONTRADICTED", "INSUFFICIENT").contains(status), "证据检查状态无效");
            var evidence = check.path("evidence");
            require(evidence.isArray() && evidence.size() <= 3 && (status.equals("INSUFFICIENT") || !evidence.isEmpty()), "判断支持或矛盾必须附原文证据");
            for (var ref : evidence) {
                require(ref.isObject() && ref.propertyNames().equals(Set.of("sourceId", "quote")), "引文必须包含sourceId和quote");
                text(ref, "sourceId", 240, false);
                String quote = text(ref, "quote", 500, false);
                var source = sources.get(ref.path("sourceId").asText());
                require(source != null && source.text().contains(quote), "证据检查引文不在已提供来源中");
            }
        }
        if (action.path("action").asText().equals("REVISE"))
            require(!checks.isEmpty() && java.util.stream.StreamSupport.stream(checks.spliterator(), false)
                    .anyMatch(c -> !c.path("status").asText().equals("SUPPORTED") && !c.path("nextAction").asText().isBlank()), "REVISE 必须指出未解决主张和下一步取证目标");
    }

    static boolean apply(ObjectNode checkpoint, JsonNode action) {
        boolean revise = action.path("action").asText().equals("REVISE");
        if (revise) require(!checkpoint.path("evidenceRevised").asBoolean(), "证据检查最多修订一次，请完成检查并保留未知");
        var checks = action.path("checks");
        checkpoint.set("evidenceReview", checks.isArray() ? checks.deepCopy() : checkpoint.arrayNode());
        checkpoint.withArray("evidenceHistory").add(action);
        if (!revise) return false;
        checkpoint.put("evidenceRevised", true);
        return true;
    }

    static void validateFinal(JsonNode payload, JsonNode checks, Map<String, Source> sources) {
        if (!checks.isArray()) checks = tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        var assessment = payload.path("assessment");
        validateItems(assessment.path("items"), checks, sources);
        validateItems(assessment.path("detail").path("items"), checks, sources);
    }

    private static void validateItems(JsonNode items, JsonNode checks, Map<String, Source> sources) {
        for (var item : items) {
            var matching = java.util.stream.StreamSupport.stream(checks.spliterator(), false)
                    .filter(c -> c.path("dimension").equals(item.path("dimension"))).findFirst();
            String conclusion = item.path("claimStatus").asText();
            if (Set.of("SUPPORTED", "CONTRADICTED").contains(conclusion))
                require(matching.isPresent(), "确定结论必须有同维度的证据检查；未检查维度保留未知或争议");
            if (matching.isPresent()) {
                String checked = matching.get().path("status").asText();
                require(conclusion.equals(checked) || Set.of("INSUFFICIENT", "DISPUTED").contains(conclusion),
                        "最终评价不能推翻已核对的证据状态或将待核对维度改为确定结论");
            }
            if (item.path("majorCorrection").asBoolean()) {
                require(matching.isPresent() && matching.get().path("status").asText().equals("CONTRADICTED"), "重大纠错必须先完成反证检查");
                require(java.util.stream.StreamSupport.stream(matching.get().path("evidence").spliterator(), false)
                        .anyMatch(e -> {
                            var source = sources.get(e.path("sourceId").asText());
                            return source != null && source.reference() && source.text().contains(e.path("quote").asText());
                        }), "重大纠错需要已提供参考资料的反证，回答自述不足以证明技术错误");
            }
        }
    }

    private static String text(JsonNode node, String key, int max, boolean empty) {
        var value = node.path(key);
        require(value.isTextual() && value.asText().length() <= max && (empty || !value.asText().isBlank()), "证据检查字段无效：" + key);
        return value.asText();
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new InterviewReviewRejected(message);
    }
}
