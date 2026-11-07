package cn.edu.whu.zhizhida.interview;

import static org.assertj.core.api.Assertions.*;

import cn.edu.whu.zhizhida.evaluation.EvaluationEngine;
import cn.edu.whu.zhizhida.knowledge.domain.KnowledgeText;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 发布前置检查：历史末次候选仅用于 R1，不冒充完整请求/工具录制回放。 */
class ReleaseReadinessTest {
    private final JsonMapper json = new JsonMapper();

    @Test void registeredGenerationMatchesThePersistentToolTransport() throws Exception {
        var candidate = json.readTree(Files.readString(Path.of("../contracts/release-v1.0.json"))).path("candidate");
        var client = new cn.edu.whu.zhizhida.ai.DeepSeekToolChatClient("unused", "http://localhost",
                candidate.path("model").asText(), candidate.path("thinking").asText(), json,
                cn.edu.whu.zhizhida.ai.AiGuardFixtures.guard(), cn.edu.whu.zhizhida.observability.BusinessMetrics.disabled());
        var generation = candidate.path("generation");
        var effective = client.executionProfile();
        assertThat(generation.path("temperature").asText()).isEqualTo(effective.get("temperature"));
        assertThat(generation.path("openingMaxTokens").asInt()).isEqualTo(Integer.parseInt(effective.get("maxOutputTokens")));
        assertThat(generation.path("reviewMaxTokens").asInt()).isEqualTo(Integer.parseInt(effective.get("maxOutputTokens")));
    }

    private List<JsonNode> history(String filename, String hash) throws Exception {
        String raw = Files.readString(Path.of("../docs/evidence", filename), StandardCharsets.UTF_8);
        assertThat(EvaluationEngine.hash(raw)).isEqualTo(hash);
        return raw.lines().filter(line -> !line.isBlank()).map(json::readTree).toList();
    }

    @Test
    void retainedOffsetFailureIsRejectedByTheExactSourceConstraint() throws Exception {
        var row = history("kernel-real-b0-3.jsonl", "b9ecf3b58f8488b980947719c966f931e024670155c51bdb29a4f6170708eaf2").getFirst();
        assertThat(row.path("config").path("loopPolicy").asText()).isEqualTo("B0");
        var reply = new InterviewOutputCodec().parseReview(json.readTree(row.path("lastPublicCandidate").asText()), "", false);
        var citation = reply.assessment().detail().items().getFirst().citations().getFirst();
        assertThat(citation.quote()).isEqualTo("我只用 Redis SETNX");
        assertThat(citation.offsetEnd()).isEqualTo(18);
        assertThat(citation.quote().length()).isEqualTo(15);
        // 末次候选首先失败于“原文不符”；修正前事件的“偏移越界”不能代替本次复放结果。
        assertThatThrownBy(() -> new EvaluationEngine().evaluateAnswer(1, "historical-answer",
                row.path("fixture").path("answer").asText(), Instant.EPOCH, reply.assessment(), "GENERAL"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("引文与指定偏移原文不一致");
    }

    @Test
    void historicalStateConflictAndSuccessfulCandidateRemainDistinct() throws Exception {
        var rows = history("kernel-real-s1-1.jsonl", "c917098b11ea6f9579c375c37d904e63a9ef48ed615a12279477af4086c92de7");
        for (var row : rows) {
            assertThat(row.path("config").path("loopPolicy").asText()).isEqualTo("S1");
            var reply = new InterviewOutputCodec().parseReview(json.readTree(row.path("lastPublicCandidate").asText()), "", false);
            String answer = row.path("fixture").path("answer").asText();
            if (row.path("caseId").asText().equals("redis-order")) {
                assertThatThrownBy(() -> new EvaluationEngine().evaluateAnswer(1, "historical-answer", answer,
                        Instant.EPOCH, reply.assessment(), "GENERAL"))
                        .isInstanceOf(IllegalArgumentException.class).hasMessage("观察与证据状态不一致");
            } else {
                assertThat(row.path("caseId").asText()).isEqualTo("adequate-owner");
                var result = new EvaluationEngine().evaluateAnswer(1, "historical-answer", answer,
                        Instant.EPOCH, reply.assessment(), "GENERAL");
                assertThat(result.detail().evidence()).hasSize(1);
                assertThat(result.detail().evidence().getFirst().quote()).isEqualTo(answer);
                assertThat(result.detail().evidence().getFirst().offsetEnd()).isEqualTo(42);
            }
        }
    }

    @Test
    void registeredDuplicateMaterialReallySplitsIntoTwoUniqueFragments() throws Exception {
        JsonNode dataset;
        try (var input = getClass().getResourceAsStream("/agent-evaluation-derived.json")) { dataset = json.readTree(input); }
        var rows = dataset.path("cases");
        assertThat(rows.size()).isEqualTo(4);
        for (var row : rows) assertThat(row.path("input").path("answer").asText().length()).isLessThanOrEqualTo(4000);
        var sample = rows.get(1).path("input");
        assertThat(sample.path("id").asText()).isEqualTo("close-owner-repeat-v1");
        String text = sample.path("material").asText(), quote = "归属检查必须限定本人范围。";
        assertThat(text).doesNotContain("\r");
        assertThat(text.split(java.util.regex.Pattern.quote(quote), -1)).hasSize(3);
        var chunks = KnowledgeText.split("closeout-repeat", 1, text);
        assertThat(chunks).hasSize(2);
        assertThat(chunks).extracting(c -> c.id()).doesNotHaveDuplicates();
        for (var chunk : chunks) {
            assertThat(chunk.content().split(java.util.regex.Pattern.quote(quote), -1)).hasSize(2);
            assertThat(text.substring(chunk.startOffset(), chunk.endOffset())).isEqualTo(chunk.content());
        }
        // 人工核对的偏移，不从生产定位函数生成期望值。
        assertThat(text.substring(12, 25)).isEqualTo(quote);
        assertThat(text.substring(38, 51)).isEqualTo(quote);
        String unicode = rows.get(3).path("input").path("answer").asText();
        assertThat(unicode.substring(42, 50)).isEqualTo("\r\n核对记录😀");
    }
}
