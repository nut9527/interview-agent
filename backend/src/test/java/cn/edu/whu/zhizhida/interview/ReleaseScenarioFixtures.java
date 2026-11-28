package cn.edu.whu.zhizhida.interview;

import static org.assertj.core.api.Assertions.assertThat;

import cn.edu.whu.zhizhida.evaluation.EvaluationEngine;
import cn.edu.whu.zhizhida.interview.api.*;
import cn.edu.whu.zhizhida.interview.api.InterviewSession.InterviewTurn;
import cn.edu.whu.zhizhida.interview.domain.InterviewAgentPolicy;
import cn.edu.whu.zhizhida.knowledge.domain.KnowledgeText;
import java.util.*;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** R2 确定性夹具，不模仿模型质量；仅执行开发/验证分区，不使用 heldout 行为字段。 */
final class ReleaseScenarioFixtures {
    static final String VERSION = "closeout-r2-v1";
    private static final JsonMapper JSON = new JsonMapper();

    static Stream<JsonNode> scenarios() throws Exception {
        byte[] bytes;
        try (var input =
                ReleaseScenarioFixtures.class.getResourceAsStream("/agent-evaluation.json")) {
            bytes = input.readAllBytes();
        }
        assertThat(
                        EvaluationEngine.hash(
                                new String(bytes, java.nio.charset.StandardCharsets.UTF_8)))
                .isEqualTo("e7a22a75b678dfdff47e9ffd4318a494b33419e9e85b3703eefcfc3822debd49");
        var selected = new ArrayList<JsonNode>();
        var data = JSON.readTree(bytes);
        assertThat(data.size()).isEqualTo(40);
        int heldout = 0;
        for (var row : data) {
            String partition = row.path("partition").asString();
            if (partition.equals("heldout")) heldout++;
            else {
                assertThat(partition).isIn("development", "validation");
                selected.add(row);
            }
        }
        assertThat(heldout).isEqualTo(10);
        assertThat(selected).hasSize(30);
        return selected.stream();
    }

    static InterviewAgentState agent(JsonNode fixture) {
        var policy = new InterviewAgentPolicy();
        var initial = policy.plan(InterviewMode.GENERAL, 6, "合成协议回归", "controlled-fixture");
        String material = fixture.path("material").asString(), id = fixture.path("id").asString();
        var excerpts =
                KnowledgeText.split(id, 1, material).stream()
                        .map(
                                c ->
                                        new KnowledgeSnapshot.Excerpt(
                                                c.id(),
                                                c.heading(),
                                                c.startLine(),
                                                c.endLine(),
                                                c.content()))
                        .toList();
        var doc =
                new KnowledgeSnapshot(
                        id,
                        fixture.path("title").asString(),
                        "合成资料",
                        1,
                        EvaluationEngine.hash(material),
                        material,
                        excerpts);
        return new InterviewAgentState(
                initial.version(),
                initial.executionMode(),
                initial.plan(),
                initial.activeCompetencyId(),
                initial.evaluations(),
                initial.digest(),
                initial.practiceMemory(),
                new InterviewTraining(null, null, null, List.of(doc)));
    }

    static String context(JsonNode fixture) {
        var state = agent(fixture);
        return InterviewAgentContext.encode(
                "合成协议回归", state, new InterviewAgentPolicy().routing(state, 1, 6));
    }

    static ObjectNode review(JsonNode fixture, String context) {
        var agent = InterviewAgentContext.decode(context);
        var route = agent.routing();
        String answer = fixture.path("answer").asString();
        var anchors =
                InterviewCitationAnchors.catalog(
                        context, List.of(new InterviewTurn("user", answer)));
        var answerAnchor =
                anchors.stream()
                        .filter(a -> a.sourceKey().equals("answer"))
                        .findFirst()
                        .orElseThrow();
        var output =
                JSON.createObjectNode()
                        .put("feedback", "回答已陈述具体做法，请补充可核对的限制。")
                        .put("nextQuestion", fixture.path("expectedGap").asString())
                        .put("finalSummary", "");
        boolean follow = route.allowedActions().contains(InterviewAgentState.Action.FOLLOW_UP);
        String target = follow ? route.current().id() : route.next().id();
        output.putObject("agentDecision")
                .put("level", "PARTIAL")
                .put("evidence", answer)
                .put("gap", fixture.path("expectedGap").asString())
                .put("action", follow ? "FOLLOW_UP" : "ADVANCE")
                .put("nextCompetencyId", target)
                .put("reason", "核对当前回答中尚未提供的依据")
                .put("practice", "补充约束、验证方法与未知结果")
                .put("probe", follow ? route.allowedProbes().getFirst().name() : "OPENING")
                .put("intent", "核对尚缺的验证依据")
                .put("questionCompetencyId", target);
        var detail =
                output.putObject("assessment")
                        .putObject("detail")
                        .put("protocolVersion", "six-dimensions-v1");
        var items = detail.putArray("items");
        for (var dimension :
                cn.edu.whu.zhizhida.evaluation.api.DetailedAssessment.Dimension.values()) {
            boolean observed =
                    dimension
                                    == cn.edu.whu.zhizhida.evaluation.api.DetailedAssessment
                                            .Dimension.RESPONSIBILITY
                            || dimension
                                    == cn.edu.whu.zhizhida.evaluation.api.DetailedAssessment
                                            .Dimension.TECHNICAL_CORRECTNESS;
            var item =
                    items.addObject()
                            .put("dimension", dimension.name())
                            .put("status", observed ? "OBSERVED" : "NOT_OBSERVED")
                            .put("claimText", observed ? "原文陈述了待核对的做法" : "未生成此维度的独立观察")
                            .put("claimStatus", observed ? "SUPPORTED" : "INSUFFICIENT")
                            .put("limitation", "合成协议响应，不代表语义评测")
                            .put("majorCorrection", false);
            var citations = item.putArray("citations");
            if (observed)
                citations
                        .addObject()
                        .put("sourceKey", "answer")
                        .put("anchorId", answerAnchor.anchorId())
                        .put("quote", answer)
                        .put("relation", "SELF_REPORT");
            if (dimension
                    == cn.edu.whu.zhizhida.evaluation.api.DetailedAssessment.Dimension
                            .TECHNICAL_CORRECTNESS) {
                String quote = fixture.path("evidenceQuote").asString();
                var reference =
                        anchors.stream()
                                .filter(
                                        a ->
                                                !a.sourceKey().equals("answer")
                                                        && a.content().contains(quote))
                                .findFirst()
                                .orElseThrow();
                citations
                        .addObject()
                        .put("sourceKey", reference.sourceKey())
                        .put("anchorId", reference.anchorId())
                        .put("quote", quote)
                        .put("relation", "SUPPORT");
            }
        }
        return output;
    }
}
