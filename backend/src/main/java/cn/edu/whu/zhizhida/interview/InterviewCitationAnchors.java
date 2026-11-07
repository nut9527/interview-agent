package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.common.Sha256;
import cn.edu.whu.zhizhida.interview.api.InterviewSession.InterviewTurn;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 固定来源片段的输入协议；只做逐字唯一定位，持久化仍交给既有身份、版本和资格校验。 */
final class InterviewCitationAnchors {
    static final String VERSION = "anchor-v1";
    private static final int SEGMENT_MAX = 800;

    record Anchor(String anchorId, String sourceKey, String sourceType, String sourceVersion,
            String sha256, String normalization, int offsetStart, int offsetEnd, String content) { }

    private InterviewCitationAnchors() { }

    static Map<String, Object> manifest() {
        return Map.of("version", VERSION, "referenceSegmentMaxUtf16", SEGMENT_MAX, "answerContent", "LATEST_USER_TRANSCRIPT",
                "normalization", "RAW_UTF16",
                "matching", "EXACT_UNIQUE_WITHIN_ANCHOR", "legacyOffsets", "STRICT_COMPATIBILITY");
    }

    static List<Anchor> catalog(String context, List<InterviewTurn> transcript) {
        var agent = InterviewAgentContext.decode(context);
        var references = agent == null || agent.checkpoint() == null
                ? List.<cn.edu.whu.zhizhida.evaluation.api.AssessmentReference>of()
                : InterviewAssessmentSources.references(0, agent.checkpoint().training());
        return catalog(transcript, references);
    }

    static List<Anchor> catalog(List<InterviewTurn> transcript, List<cn.edu.whu.zhizhida.evaluation.api.AssessmentReference> references) {
        var result = new ArrayList<Anchor>();
        String answer = transcript.reversed().stream().filter(t -> "user".equals(t.speaker()))
                .map(InterviewTurn::content).findFirst().orElse("");
        add(result, "answer", "ANSWER_SELF_REPORT", Sha256.hex(answer), answer, Math.max(1, answer.length()));
        for (var ref : references) add(result, ref.key(), ref.sourceType(), ref.version(), ref.text(), SEGMENT_MAX);
        return List.copyOf(result);
    }

    private static void add(List<Anchor> out, String key, String type, String version, String text, int maximum) {
        String hash = Sha256.hex(text);
        for (int start = 0; start < text.length();) {
            int end = Math.min(start + maximum, text.length());
            // 切片不拆开代理对或 CRLF；偏移量按原文 UTF-16 代码单元计数，不是字节位置。
            if (end < text.length() && (Character.isHighSurrogate(text.charAt(end - 1))
                    || text.charAt(end - 1) == '\r' && text.charAt(end) == '\n')) end--;
            String id = Sha256.hex(VERSION + "\n" + key + "\n" + type + "\n" + version + "\n" + hash
                    + "\nRAW_UTF16\n" + start + ":" + end);
            out.add(new Anchor(id, key, type, version, hash, "RAW_UTF16", start, end, text.substring(start, end)));
            start = end;
        }
    }

    /** 只转换模型输入表示；不搜索其他来源，不改写引文，不补齐状态，也不改变最终证据格式。 */
    static JsonNode resolve(JsonNode payload, List<Anchor> catalog) {
        var copy = payload.deepCopy();
        for (var item : copy.path("assessment").path("items"))
            for (var quote : item.path("quotes")) locate(quote, "answer", catalog);
        for (var item : copy.path("assessment").path("detail").path("items"))
            for (var quote : item.path("citations")) locate(quote, quote.path("sourceKey").asText(), catalog);
        return copy;
    }

    private static void locate(JsonNode node, String key, List<Anchor> catalog) {
        if (!node.has("anchorId")) return; // 原偏移协议供历史回放及旧调用方使用，仍须经过原校验。
        if (!node.isObject() || !node.path("anchorId").isTextual() || !node.path("quote").isTextual()
                || node.has("offsetStart") || node.has("offsetEnd")) reject("anchor 引用结构无效，不能混用偏移协议");
        var value = (ObjectNode) node;
        String quote = node.path("quote").asText(), id = node.path("anchorId").asText();
        var anchor = catalog.stream().filter(a -> a.anchorId().equals(id) && a.sourceKey().equals(key)).findFirst()
                .orElseThrow(() -> new InterviewReviewRejected("anchor 不属于本轮固定来源或版本"));
        if (quote.isBlank() || quote.length() > InterviewOutputContract.EVIDENCE_MAX) reject("anchor 引文为空或超限");
        int start = anchor.content().indexOf(quote);
        if (start < 0) reject("anchor 片段不含逐字引文");
        if (anchor.content().indexOf(quote, start + 1) >= 0) reject("anchor 片段内引文不唯一，请提供更长引文");
        int end = start + quote.length();
        if (!boundary(anchor.content(), start) || !boundary(anchor.content(), end)) reject("anchor 引文拆开 UTF-16 代理对");
        value.remove("anchorId");
        value.put("offsetStart", anchor.offsetStart() + start).put("offsetEnd", anchor.offsetStart() + end);
    }

    private static boolean boundary(String text, int position) {
        return position == 0 || position == text.length() || !Character.isHighSurrogate(text.charAt(position - 1))
                || !Character.isLowSurrogate(text.charAt(position));
    }
    private static void reject(String reason) { throw new InterviewReviewRejected(reason); }
}
