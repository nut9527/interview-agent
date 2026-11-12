package cn.edu.whu.zhizhida.interview;

import static org.assertj.core.api.Assertions.*;

import cn.edu.whu.zhizhida.evaluation.EvaluationEngine;
import cn.edu.whu.zhizhida.evaluation.api.AssessmentReference;
import cn.edu.whu.zhizhida.evaluation.api.AssessmentResult.Normalization;
import cn.edu.whu.zhizhida.evaluation.api.AssessmentResult.Validity;
import cn.edu.whu.zhizhida.interview.api.InterviewSession.InterviewTurn;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 历史裸输出只作协议及引用回放，不再通过生产 Worker 执行已退役协议。 */
class CloseoutRecordedProtocolTest {
    private final JsonMapper json = new JsonMapper();

    @ParameterizedTest(name = "archived response and repair {0}")
    @ValueSource(ints = {0, 1})
    void originalResponsesRetainAnchorsFailuresAndEvidence(int sample) throws Exception {
        var recorded = CloseoutRecordedFixtures.report().get(sample);
        var exchanges = CloseoutRecordedFixtures.exchange(sample);
        var prompt = CloseoutRecordedFixtures.prompt(json.readTree(exchanges.getFirst().path("body").asText()));
        var sources = new ArrayList<AssessmentReference>();
        for (var source : prompt.path("untrustedEvidence").path("assessmentSources")) {
            String text = source.path("content").asText();
            assertThat(EvaluationEngine.hash(text)).isEqualTo(source.path("sha256").asText());
            sources.add(new AssessmentReference(source.path("sourceKey").asText(), 1,
                    source.path("sourceType").asText(), source.path("sourceKey").asText(),
                    source.path("sourceVersion").asText(), text, source.path("sha256").asText(),
                    Normalization.RAW_UTF16, Validity.VALID));
        }
        String answer = recorded.path("fixture").path("answer").asText();
        var anchors = InterviewCitationAnchors.catalog(List.of(new InterviewTurn("user", answer)), sources);
        assertThat(anchors).hasSize(prompt.path("citationAnchors").size());
        for (int i = 0; i < anchors.size(); i++) {
            var actual = json.valueToTree(anchors.get(i));
            var original = prompt.path("citationAnchors").get(i);
            for (String field : List.of("anchorId", "sourceKey", "sourceType", "sourceVersion", "sha256", "normalization", "offsetStart", "offsetEnd"))
                assertThat(actual.path(field)).as(field).isEqualTo(original.path(field));
        }
        for (int ordinal = 0; ordinal < exchanges.size() / 2; ordinal++) {
            var request = json.readTree(exchanges.get(ordinal * 2).path("body").asText());
            var response = json.readTree(exchanges.get(ordinal * 2 + 1).path("body").asText());
            var choice = response.path("choices").get(0);
            String text = choice.path("message").path("content").asText();
            var payload = json.readTree(text);
            assertThat(choice.path("finish_reason").asText()).isEqualTo("stop");
            assertThat(choice.path("message").has("tool_calls")).isFalse();
            assertThatThrownBy(() -> InterviewDecisionContract.parse(json, text, "FINAL"))
                    .isInstanceOf(InterviewReviewRejected.class);
            var envelope = json.createObjectNode().put("version", "phased-v2").put("phase", "FINAL").set("result", payload);
            assertThat(InterviewDecisionContract.parse(json, envelope.toString(), "FINAL")).isEqualTo(payload);
            var reply = new InterviewOutputCodec().parseReviewWithAnchors(payload, "", false, anchors);
            if (sample == 1 && ordinal == 0) {
                assertThatThrownBy(() -> new EvaluationEngine().evaluateAnswer(1, "archived-answer", answer,
                        Instant.EPOCH, reply.assessment(), "GENERAL", sources))
                        .isInstanceOf(IllegalArgumentException.class).hasMessage("观察与证据状态不一致");
                continue;
            }
            if (sample == 1) assertThat(request.toString()).contains("SEMANTIC_MISMATCH");
            var assessment = new EvaluationEngine().evaluateAnswer(1, "archived-answer", answer,
                    Instant.EPOCH, reply.assessment(), "GENERAL", sources);
            var evidence = json.valueToTree(assessment.detail()).path("evidence");
            var originalEvidence = recorded.path("evaluation").path("assessment").path("detail").path("evidence");
            assertThat(evidence.size()).isEqualTo(originalEvidence.size());
            for (int i = 0; i < evidence.size(); i++)
                for (String field : List.of("quote", "offsetStart", "offsetEnd", "sourceVersion", "sourceHash"))
                    assertThat(evidence.get(i).path(field)).as(field).isEqualTo(originalEvidence.get(i).path(field));
            assertThat(payload.path("agentDecision")).isEqualTo(recorded.path("evaluation").path("decision"));
        }
        assertThat(recorded.path("toolExecutions").asInt()).isZero();
        assertThat(recorded.path("attempts").asInt()).isEqualTo(exchanges.size() / 2);
        assertThat(recorded.path("repairs").asInt()).isEqualTo(sample);
    }
}
