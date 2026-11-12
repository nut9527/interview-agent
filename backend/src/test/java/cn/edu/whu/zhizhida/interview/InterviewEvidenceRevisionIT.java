package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.ai.api.*;
import cn.edu.whu.zhizhida.interview.api.*;
import cn.edu.whu.zhizhida.interview.domain.*;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "interview.runs.worker-enabled=false", "ai.provider=deepseek",
        "interview.runs.decision-protocol=phased-v2", "interview.runs.loop-policy=A1"})
class InterviewEvidenceRevisionIT extends InterviewRunScenario {
    @MockitoBean ToolCallingChatClient client;
    @Autowired InterviewSessionRepository sessions;
    @Autowired InterviewStateMachine machine;
    private static final String ANSWER = "我用唯一约束保存请求号，并把业务写入放在同一事务中。";

    @BeforeEach void provider() {
        when(client.configuration()).thenReturn(new ToolCallingChatClient.Config("revision-fixture", "disabled", "v1"));
    }

    private InterviewSession session() throws Exception {
        var saved = performPost("/api/knowledge/documents", Map.of("title", "事务幂等测试资料", "content",
                "# 事务提交\n请求号与业务结果在同一数据库事务中提交；提交失败应回滚。\n\n# 重放测试\n重放相同请求号应返回首次结果，用故障注入核对重复执行没有副作用。"), token);
        assertThat(saved.getResponse().getStatus()).isEqualTo(200);
        var document = objectMapper.valueToTree(dataMap(saved));
        var excerpts = new ArrayList<KnowledgeSnapshot.Excerpt>();
        for (var chunk : document.path("chunks")) excerpts.add(new KnowledgeSnapshot.Excerpt(chunk.path("id").asText(),
                chunk.path("heading").asText(), chunk.path("startLine").asInt(), chunk.path("endLine").asInt(), chunk.path("content").asText()));
        var doc = new KnowledgeSnapshot(document.path("id").asText(), document.path("title").asText(), "fixture",
                document.path("version").asInt(), cn.edu.whu.zhizhida.common.Sha256.hex(document.path("content").asText()),
                document.path("content").asText(), excerpts);
        var training = new InterviewTraining(InterviewTraining.Topic.DATABASE, null, null, List.of(doc));
        var policy = new InterviewAgentPolicy();
        var agent = policy.trainingPlan(training, new InterviewPlanningContext("数据库事务", List.of(), List.of(), List.of(), ""), "model", List.of(), null);
        var session = new InterviewSession(UUID.randomUUID().toString(), "Java", null, "", null, null, "", InterviewMode.BACKEND_BASICS,
                3, null, InterviewState.WAITING_ANSWER, 1, List.of(new InterviewSession.InterviewTurn("interviewer", "数据库事务如何保证请求幂等？"))).withAgent(agent);
        sessions.save(owner, session);
        return session;
    }

    private ToolCallingChatClient.Response response(JsonNode output) {
        return new ToolCallingChatClient.Response(objectMapper.createObjectNode().put("role", "assistant").put("content", output.toString()),
                "stop", new ModelUsage(ModelUsage.Status.REPORTED, 20L, 10L, 30L));
    }

    private ToolCallingChatClient.Response search(String id, String query) {
        var message = objectMapper.createObjectNode().put("role", "assistant").putNull("content");
        message.putArray("tool_calls").addObject().put("id", id).put("type", "function").putObject("function")
                .put("name", "search_knowledge").put("arguments", objectMapper.writeValueAsString(Map.of("query", query, "topK", 1)));
        return new ToolCallingChatClient.Response(message, "tool_calls", ModelUsage.unknown(ModelUsage.Status.MISSING));
    }

    private ObjectNode check(String action, String status, List<Map<String, String>> evidence) {
        var node = objectMapper.createObjectNode().put("version", "phased-v2").put("phase", "ACTION")
                .put("action", action).put("reason", "核对事务与重复执行的边界");
        var check = node.putArray("checks").addObject().put("dimension", "TECHNICAL_CORRECTNESS")
                .put("claim", "同一事务能保证重放没有重复副作用").put("status", status)
                .put("nextAction", action.equals("REVISE") ? "改查重放测试与故障注入证据" : "保留本次检查的适用范围");
        check.set("evidence", objectMapper.valueToTree(evidence));
        return node;
    }

    private ObjectNode finalOutput(InterviewSession session) {
        var pending = (InterviewTransitions.PendingAnswer) new InterviewTransitions().answer(session, ANSWER, "fixture-answer", 1);
        var mock = new MockInterviewAiService(objectMapper);
        var result = mock.generateReview(session.targetRole(), machine.runContext(session, pending, List.of()), pending.turns(), 1, false);
        return objectMapper.createObjectNode().put("version", "phased-v2").put("phase", "FINAL").set("result", result);
    }

    @Test void revisesTheSearchAndPersistsTheUpdatedEvidenceBeforeFinalGeneration() throws Exception {
        var session = session();
        var calls = new AtomicInteger();
        var result = finalOutput(session);
        when(client.complete(any())).thenAnswer(inv -> {
            ToolCallingChatClient.Request request = inv.getArgument(0);
            return switch (calls.incrementAndGet()) {
                case 1 -> search("first-query", "事务提交");
                case 2 -> response(check("REVISE", "INSUFFICIENT", List.of()));
                case 3 -> {
                    assertThat(request.messages().toString()).contains("改查重放测试", "重新提交完整 checks");
                    yield search("revised-query", "重放测试 故障注入");
                }
                case 4 -> {
                    var receipt = request.messages().stream().filter(m -> "revised-query".equals(m.path("tool_call_id").asText())).findFirst().orElseThrow();
                    var reference = objectMapper.readTree(receipt.path("content").asText()).path("references").get(0);
                    var excerpt = reference.path("excerpts").get(0);
                    yield response(check("COMPLETE", "SUPPORTED", List.of(Map.of("sourceId", "knowledge/" + reference.path("id").asText() + "/" + excerpt.path("chunkId").asText(),
                            "quote", excerpt.path("content").asText()))));
                }
                default -> {
                    assertThat(request.toolsAllowed()).isFalse();
                    assertThat(request.messages().toString()).contains("SUPPORTED", "重放测试");
                    yield response(result);
                }
            };
        });
        String id = acceptAnswer(session.sessionId(), "revised-answer", 1, ANSWER);
        runSuccessfully(id);
        var row = store.get(owner, id);
        assertThat(row.attempts()).isEqualTo(5);
        assertThat(row.tools()).isEqualTo(2);
        var cp = objectMapper.readTree(row.checkpoint());
        assertThat(cp.path("evidenceHistory")).hasSize(2);
        assertThat(cp.path("evidenceHistory").get(0).path("action").asText()).isEqualTo("REVISE");
        assertThat(cp.path("evidenceReview").get(0).path("status").asText()).isEqualTo("SUPPORTED");
        assertThat(sessions.findById(owner, session.sessionId()).orElseThrow().turnCount()).isEqualTo(2);
        clearInvocations(client);
        assertThat(acceptAnswer(session.sessionId(), "revised-answer", 1, ANSWER)).isEqualTo(id);
        verifyNoInteractions(client);
    }

    @Test void finalCannotPromoteAnUnresolvedTechnicalClaimAndRepairKeepsTheCheck() throws Exception {
        var session = session();
        var valid = finalOutput(session);
        var unsupported = valid.deepCopy();
        for (var item : unsupported.path("result").path("assessment").path("detail").path("items")) {
            if (item.path("dimension").asText().equals("TECHNICAL_CORRECTNESS"))
                ((ObjectNode)item).put("status", "OBSERVED").put("claimStatus", "SUPPORTED");
        }
        when(client.complete(any())).thenReturn(response(check("COMPLETE", "INSUFFICIENT", List.of())), response(unsupported), response(valid));
        String id = acceptAnswer(session.sessionId(), "unresolved-answer", 1, ANSWER);
        runSuccessfully(id);
        assertThat(store.get(owner, id).attempts()).isEqualTo(3);
        assertThat(store.get(owner, id).repairs()).isEqualTo(1);
        var check = objectMapper.readTree(store.get(owner, id).checkpoint()).path("evidenceReview").get(0);
        assertThat(check.path("status").asText()).isEqualTo("INSUFFICIENT");
        var detail = sessions.findById(owner, session.sessionId()).orElseThrow().agent().evaluations().getFirst().assessment().detail();
        assertThat(detail.items()).filteredOn(i -> i.dimension().name().equals("TECHNICAL_CORRECTNESS"))
                .allSatisfy(i -> assertThat(i.claim().status().name()).isEqualTo("INSUFFICIENT"));
    }
}
