package cn.edu.whu.zhizhida.interview;

import static org.assertj.core.api.Assertions.*;

import cn.edu.whu.zhizhida.evaluation.EvaluationEngine;
import cn.edu.whu.zhizhida.evaluation.api.AssessmentReference;
import cn.edu.whu.zhizhida.evaluation.api.AssessmentResult;
import cn.edu.whu.zhizhida.common.Sha256;
import cn.edu.whu.zhizhida.interview.api.InterviewSession.InterviewTurn;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class InterviewCitationAnchorsTest {
    private final JsonMapper json = new JsonMapper();

    private List<InterviewCitationAnchors.Anchor> anchors(String answer) {
        return InterviewCitationAnchors.catalog("", List.of(new InterviewTurn("user", answer)));
    }

    private List<InterviewCitationAnchors.Anchor> referenceAnchors(String text) {
        return InterviewCitationAnchors.catalog(List.of(), List.of(new AssessmentReference(
                "knowledge/test", 1L, "KNOWLEDGE_EXCERPT", "test", "1", text, Sha256.hex(text),
                AssessmentResult.Normalization.RAW_UTF16, AssessmentResult.Validity.VALID)));
    }

    private ObjectNode candidate(String id, String quote) {
        var root = json.createObjectNode().put("feedback", "明确了行动，还需补充验证限制。")
                .put("nextQuestion", "请说明验证失败时的处理依据？");
        var items = root.putObject("assessment").putArray("items");
        for (String dimension : List.of("RESPONSIBILITY", "CAUSAL_EXPLANATION")) {
            var item = items.addObject().put("dimension", dimension).put("status", "OBSERVED")
                    .put("claimText", "陈述了本人行动").put("claimStatus", "SUPPORTED").put("limitation", "自述待核实");
            item.putArray("quotes").addObject().put("anchorId", id).put("quote", quote);
        }
        return root;
    }

    private JsonNode quote(JsonNode result) { return result.path("assessment").path("items").get(0).path("quotes").get(0); }

    @Test void rawChineseCrLfAndEmojiLocateToIndependentlyCheckedOffsetsAndPersistAsOldEvidence() {
        String answer = "甲\r\n核对😀：本人查询成功。";
        var payload = candidate(anchors(answer).getFirst().anchorId(), "核对😀");
        var parsed = new InterviewOutputCodec().parseReview(payload, "", false, List.of(new InterviewTurn("user", answer)));
        var result = new EvaluationEngine().evaluateAnswer(7, "answer-1", answer, Instant.EPOCH, parsed.assessment(), "GENERAL");
        assertThat(result.evidence()).hasSize(1);
        assertThat(result.evidence().getFirst().offsetStart()).isEqualTo(3);
        assertThat(result.evidence().getFirst().offsetEnd()).isEqualTo(7);
        assertThat(result.evidence().getFirst().quote()).isEqualTo("核对😀");
        assertThat(payload.toString()).contains("anchorId").doesNotContain("offsetStart");
    }

    @Test void repeatedTextInOtherFragmentsIsAllowedButWithinOneFragmentRequiresLongerQuote() {
        String text = "相同引文。" + "甲".repeat(795) + "相同引文。本人写入。";
        var catalog = referenceAnchors(text);
        assertThat(catalog).hasSize(2);
        var payload = json.createObjectNode();
        payload.putObject("assessment").putObject("detail").putArray("items").addObject().putArray("citations")
                .addObject().put("sourceKey", "knowledge/test").put("anchorId", catalog.get(1).anchorId()).put("quote", "相同引文。");
        var result = InterviewCitationAnchors.resolve(payload, catalog);
        assertThat(result.path("assessment").path("detail").path("items").get(0).path("citations").get(0)
                .path("offsetStart").asInt()).isEqualTo(800);
        var repeated = anchors("甲甲乙");
        assertThatThrownBy(() -> InterviewCitationAnchors.resolve(candidate(repeated.getFirst().anchorId(), "甲"), repeated))
                .hasMessageContaining("不唯一");
        assertThat(quote(InterviewCitationAnchors.resolve(candidate(repeated.getFirst().anchorId(), "甲乙"), repeated))
                .path("offsetStart").asInt()).isEqualTo(1);
        // 包括重叠匹配，不能仅检查不重叠的重复。
        var overlap = anchors("甲甲甲");
        assertThatThrownBy(() -> InterviewCitationAnchors.resolve(candidate(overlap.getFirst().anchorId(), "甲甲"), overlap))
                .hasMessageContaining("不唯一");
    }

    @Test void changedVersionWrongSourceNormalizationAndMixedOffsetsAreRejected() {
        var original = anchors("甲\r\n乙😀");
        var payload = candidate(original.getFirst().anchorId(), "乙😀");
        assertThatThrownBy(() -> InterviewCitationAnchors.resolve(payload, anchors("甲\n乙😀"))).hasMessageContaining("固定来源或版本");
        assertThatThrownBy(() -> InterviewCitationAnchors.resolve(payload, anchors("甲\r\n乙😀改变"))).hasMessageContaining("固定来源或版本");
        ((ObjectNode) quote(payload)).put("offsetStart", 3);
        assertThatThrownBy(() -> InterviewCitationAnchors.resolve(payload, original)).hasMessageContaining("混用");
        assertThatThrownBy(() -> InterviewCitationAnchors.resolve(candidate(original.getFirst().anchorId(), "乙\uD83D"), original))
                .hasMessageContaining("代理对");
        assertThatThrownBy(() -> InterviewCitationAnchors.resolve(candidate(original.getFirst().anchorId(), "不存在"), original))
                .hasMessageContaining("不含逐字");
        var wrongKey = json.createObjectNode();
        wrongKey.putObject("assessment").putObject("detail").putArray("items").addObject().putArray("citations")
                .addObject().put("sourceKey", "knowledge/other").put("anchorId", original.getFirst().anchorId()).put("quote", "乙😀");
        assertThatThrownBy(() -> InterviewCitationAnchors.resolve(wrongKey, original)).hasMessageContaining("固定来源或版本");
    }

    @Test void fragmentCutsPreserveSurrogatesCrLfAndLatestUserAnswer() {
        for (String text : List.of("甲".repeat(799) + "😀乙", "甲".repeat(799) + "\r\n乙")) {
            var catalog = referenceAnchors(text);
            assertThat(catalog.getFirst().offsetEnd()).isEqualTo(799);
            assertThat(catalog.stream().map(InterviewCitationAnchors.Anchor::content).reduce("", String::concat)).isEqualTo(text);
        }
        var actual = InterviewCitationAnchors.catalog("", List.of(new InterviewTurn("user", "旧回答"),
                new InterviewTurn("user", "当前回答"), new InterviewTurn("interviewer", "旧问题")));
        assertThat(actual).isEqualTo(anchors("当前回答"));
    }

}
