package cn.edu.whu.zhizhida.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.sun.net.httpserver.HttpServer;

import cn.edu.whu.zhizhida.ai.DeepSeekToolChatClient;
import cn.edu.whu.zhizhida.ai.McpReferenceFixture;
import cn.edu.whu.zhizhida.ai.api.ModelUsage;
import cn.edu.whu.zhizhida.ai.api.ToolCallingChatClient;
import cn.edu.whu.zhizhida.interview.api.InterviewSession;
import cn.edu.whu.zhizhida.interview.execution.AgentLoopState;
import cn.edu.whu.zhizhida.interview.execution.InterviewEvidenceTools;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunStore;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunWorker;
import cn.edu.whu.zhizhida.interview.execution.InterviewToolProvider;
import tools.jackson.databind.json.JsonMapper;

class InterviewModelToolsIT {
        private static boolean actionPhase(ToolCallingChatClient.Request request) {
                return request.messages()
                                .getFirst()
                                .path("content")
                                .asString()
                                .startsWith(InterviewDecisionContract.ACTION_PROMPT);
        }

        private static ToolCallingChatClient.Response phaseResult(
                        tools.jackson.databind.ObjectMapper json,
                        ToolCallingChatClient.Request request,
                        tools.jackson.databind.JsonNode result) {
                var envelope = json.createObjectNode().put("version", "phased-v2");
                if (actionPhase(request)) {
                        envelope.put("phase", "ACTION").put("action", "COMPLETE").put("reason", "合成协议检查完成");
                        var checks = envelope.putArray("checks");
                        for (var item : result.path("assessment").path("detail").path("items")) {
                                var check = checks.addObject()
                                                .put("dimension", item.path("dimension").asString())
                                                .put("claim", item.path("claimText").asString())
                                                .put("status", item.path("claimStatus").asString())
                                                .put("nextAction", "");
                                var evidence = check.putArray("evidence");
                                if (!item.path("citations").isEmpty()) {
                                        var citation = item.path("citations").get(0);
                                        String quote = citation.path("quote").asString();
                                        evidence.addObject()
                                                        .put(
                                                                        "sourceId",
                                                                        citation.path("sourceKey").asString()
                                                                                        .equals("answer")
                                                                                                        ? "answer-1"
                                                                                                        : citation.path("sourceKey")
                                                                                                                        .asString())
                                                        .put("quote", quote.substring(0,
                                                                        Math.min(100, quote.length())));
                                }
                        }
                } else
                        envelope.put("phase", "FINAL").set("result", result);
                return new ToolCallingChatClient.Response(
                                json.createObjectNode()
                                                .put("role", "assistant")
                                                .put("content", envelope.toString()),
                                "stop",
                                new ModelUsage(ModelUsage.Status.REPORTED, 10L, 5L, 15L));
        }

        abstract static class ToolScenario extends InterviewRunScenario {
                @MockitoBean
                ToolCallingChatClient client;
                @Autowired
                JdbcTemplate jdbc;

                @BeforeEach
                void setup() throws Exception {
                        when(client.configuration())
                                        .thenReturn(
                                                        new ToolCallingChatClient.Config(
                                                                        "controlled-fixture", "enabled", "fixture-v1"));
                }

                String start() throws Exception {
                        return acceptStart(
                                        Map.of(
                                                        "targetRole",
                                                        "Java 后端",
                                                        "resumeSummary",
                                                        "项目使用 Redis 保存幂等键；数据库异常处理未描述。",
                                                        "totalRounds",
                                                        3));
                }

                ToolCallingChatClient.Response finalOutput(ToolCallingChatClient.Request request) {
                        return phaseResult(
                                        objectMapper,
                                        request,
                                        objectMapper.readTree(
                                                        "{\"question\":\"请说明你在相关经历中承担的个人职责？\",\"questionCompetencyId\":\"experience\"}"));
                }

                ToolCallingChatClient.Response finalOutput() {
                        return new ToolCallingChatClient.Response(
                                        objectMapper
                                                        .createObjectNode()
                                                        .put("role", "assistant")
                                                        .put(
                                                                        "content",
                                                                        "{\"version\":\"phased-v2\",\"phase\":\"FINAL\",\"result\":{\"question\":\"请说明你在相关经历中承担的个人职责？\",\"questionCompetencyId\":\"experience\"}}"),
                                        "stop",
                                        ModelUsage.unknown(ModelUsage.Status.MISSING));
                }
        }

        @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                        "interview.runs.worker-enabled=false",
                        "ai.provider=deepseek",
                        "interview.runs.loop-policy=A1"
        })
        @Nested
        class Tools extends ToolScenario {
                @MockitoSpyBean
                InterviewEvidenceTools tools;
                @Autowired
                InterviewSessionRepository sessions;

                ToolCallingChatClient.Response toolOutput(String id, String args) {
                        return toolOutput(id, "read_material_section", args);
                }

                ToolCallingChatClient.Response toolOutput(String id, String name, String args) {
                        var message = objectMapper
                                        .createObjectNode()
                                        .put("role", "assistant")
                                        .putNull("content")
                                        .put("reasoning_content", "private fixture state");
                        message.putArray("tool_calls")
                                        .addObject()
                                        .put("id", id)
                                        .put("type", "function")
                                        .putObject("function")
                                        .put("name", name)
                                        .put("arguments", args);
                        return new ToolCallingChatClient.Response(
                                        message,
                                        "tool_calls",
                                        new ModelUsage(ModelUsage.Status.REPORTED, 10L, 5L, 15L));
                }

                private InterviewSession saveScenarioSession(
                                tools.jackson.databind.JsonNode fixture, boolean headingOnly) throws Exception {
                        // 先由真实受理接口保存资料，生产提交仍检查归属、固定版本与来源资格。
                        var saved = performPost(
                                        "/api/knowledge/documents",
                                        Map.of(
                                                        "title",
                                                        fixture.path("title").asString(),
                                                        "content",
                                                        fixture.path("material").asString()),
                                        token);
                        assertThat(saved.getResponse().getStatus()).isEqualTo(200);
                        var document = objectMapper
                                        .readTree(
                                                        saved.getResponse()
                                                                        .getContentAsString(
                                                                                        java.nio.charset.StandardCharsets.UTF_8))
                                        .path("data");
                        var excerpts = new java.util.ArrayList<cn.edu.whu.zhizhida.interview.api.KnowledgeSnapshot.Excerpt>();
                        for (var c : document.path("chunks"))
                                excerpts.add(
                                                new cn.edu.whu.zhizhida.interview.api.KnowledgeSnapshot.Excerpt(
                                                                c.path("id").asString(),
                                                                c.path("heading").asString(),
                                                                c.path("startLine").asInt(),
                                                                c.path("endLine").asInt(),
                                                                c.path("content").asString()));
                        var doc = new cn.edu.whu.zhizhida.interview.api.KnowledgeSnapshot(
                                        document.path("id").asString(),
                                        document.path("title").asString(),
                                        "合成回归",
                                        document.path("version").asInt(),
                                        InterviewRunStore.sha(document.path("content").asString()),
                                        document.path("content").asString(),
                                        headingOnly ? List.of(excerpts.getFirst()) : excerpts);
                        var base = ReleaseScenarioFixtures.agent(fixture);
                        var agent = new cn.edu.whu.zhizhida.interview.api.InterviewAgentState(
                                        base.version(),
                                        base.executionMode(),
                                        base.plan(),
                                        base.activeCompetencyId(),
                                        base.evaluations(),
                                        base.digest(),
                                        base.practiceMemory(),
                                        new cn.edu.whu.zhizhida.interview.api.InterviewTraining(
                                                        null, null, null, java.util.List.of(doc)));
                        String sid = UUID.randomUUID().toString();
                        var session = new cn.edu.whu.zhizhida.interview.api.InterviewSession(
                                        sid,
                                        "Java 后端",
                                        null,
                                        "合成回归资料",
                                        null,
                                        null,
                                        "Java 后端",
                                        cn.edu.whu.zhizhida.interview.api.InterviewMode.GENERAL,
                                        6,
                                        null,
                                        cn.edu.whu.zhizhida.interview.api.InterviewState.WAITING_ANSWER,
                                        1,
                                        java.util.List.of(
                                                        new cn.edu.whu.zhizhida.interview.api.InterviewSession.InterviewTurn(
                                                                        "interviewer",
                                                                        fixture.path("question").asString())),
                                        null,
                                        agent,
                                        cn.edu.whu.zhizhida.interview.api.InterviewDifficulty.STANDARD);
                        sessions.save(owner, session);
                        return session;
                }

                @org.junit.jupiter.params.ParameterizedTest(name = "R2 Worker {index}")
                @org.junit.jupiter.params.provider.MethodSource("cn.edu.whu.zhizhida.interview.ReleaseScenarioFixtures#scenarios")
                void registeredScenariosReachProductionToolParserAndAtomicCommit(
                                tools.jackson.databind.JsonNode fixture) throws Exception {
                        var session = saveScenarioSession(fixture, false);
                        String sid = session.sessionId();
                        String context = InterviewAgentContext.encode(
                                        session.resumeSnapshot(),
                                        session.agent(),
                                        new cn.edu.whu.zhizhida.interview.domain.InterviewAgentPolicy()
                                                        .routing(session.agent(), 1, 6));
                        var output = ReleaseScenarioFixtures.review(fixture, context);
                        boolean useTool = !fixture.path("reasonableTools").isEmpty();
                        var calls = new AtomicInteger();
                        when(client.complete(any()))
                                        .thenAnswer(
                                                        inv -> {
                                                                ToolCallingChatClient.Request request = inv
                                                                                .getArgument(0);
                                                                if (useTool && calls.getAndIncrement() == 0)
                                                                        return toolOutput(
                                                                                        "r2-read",
                                                                                        "{\"sectionId\":\"resume-0\",\"maxChars\":2000}");
                                                                if (useTool)
                                                                        assertThat(
                                                                                        request.messages().stream()
                                                                                                        .filter(
                                                                                                                        m -> m.path("role")
                                                                                                                                        .asString()
                                                                                                                                        .equals(
                                                                                                                                                        "tool")))
                                                                                        .anySatisfy(
                                                                                                        m -> assertThat(
                                                                                                                        m.path("tool_call_id")
                                                                                                                                        .asString())
                                                                                                                        .isEqualTo("r2-read"));
                                                                return phaseResult(objectMapper, request, output);
                                                        });
                        String request = UUID.randomUUID().toString(),
                                        path = "/api/interviews/sessions/" + sid + "/answer-commands";
                        var body = Map.of(
                                        "requestId",
                                        request,
                                        "expectedTurn",
                                        1,
                                        "answer",
                                        fixture.path("answer").asString());
                        var accepted = performPost(path, body, token);
                        assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
                        String id = (String) dataMap(accepted).get("runId");
                        run(id);
                        assertThat(store.get(owner, id).status())
                                        .as(fixture.path("id").asString())
                                        .isEqualTo("SUCCEEDED");
                        assertThat(store.get(owner, id).tools()).isEqualTo(useTool ? 1 : 0);
                        assertThat(store.get(owner, id).repairs()).isZero();
                        var committed = sessions.findById(owner, sid).orElseThrow();
                        assertThat(committed.turnCount()).isEqualTo(2);
                        assertThat(committed.agent().evaluations()).hasSize(1);
                        assertThat(objectMapper.writeValueAsString(committed))
                                        .contains("six-dimensions-v1", "offsetStart")
                                        .doesNotContain("anchorId");
                        assertThat(dataMap(performPost(path, body, token)).get("runId")).isEqualTo(id);
                        assertThat(sessions.findById(owner, sid).orElseThrow().turnCount()).isEqualTo(2);
                }

                @Test
                void retrievedKnowledgeAnchorFromTheModelRequestCommitsAnExactCitation() throws Exception {
                        var fixture = ReleaseScenarioFixtures.scenarios()
                                        .filter(row -> row.path("id").asString().equals("redis-order"))
                                        .findFirst()
                                        .orElseThrow();
                        var session = saveScenarioSession(fixture, true);
                        var source = session.agent().training().knowledge().getFirst();
                        String quote = fixture.path("evidenceQuote").asString();
                        var output = ReleaseScenarioFixtures.review(
                                        fixture, ReleaseScenarioFixtures.context(fixture));
                        var citation = (tools.jackson.databind.node.ObjectNode) output
                                        .at("/assessment/detail/items/1/citations/1");
                        var calls = new AtomicInteger();
                        when(client.complete(any()))
                                        .thenAnswer(
                                                        inv -> {
                                                                ToolCallingChatClient.Request request = inv
                                                                                .getArgument(0);
                                                                var user = request.messages().stream()
                                                                                .filter(
                                                                                                message -> message.path(
                                                                                                                "role")
                                                                                                                .asString()
                                                                                                                .equals("user"))
                                                                                .findFirst()
                                                                                .orElseThrow();
                                                                // 第一条用户消息的首行是普通评估 JSON，接下来才是独立的材料清单。
                                                                var input = objectMapper.readTree(
                                                                                user.path("content")
                                                                                                .asString()
                                                                                                .lines()
                                                                                                .findFirst()
                                                                                                .orElseThrow());
                                                                if (calls.getAndIncrement() == 0) {
                                                                        assertThat(request.messages().toString())
                                                                                        .doesNotContain(quote);
                                                                        return toolOutput(
                                                                                        "retrieve",
                                                                                        "search_knowledge",
                                                                                        "{\"query\":\"数据库失败重试\",\"topK\":1}");
                                                                }
                                                                var receipt = objectMapper.readTree(
                                                                                request.messages().stream()
                                                                                                .filter(
                                                                                                                message -> message
                                                                                                                                .path("tool_call_id")
                                                                                                                                .asString()
                                                                                                                                .equals("retrieve"))
                                                                                                .findFirst()
                                                                                                .orElseThrow()
                                                                                                .path("content")
                                                                                                .asString());
                                                                var hit = receipt.at("/references/0/excerpts/0");
                                                                assertThat(hit.path("content").asString())
                                                                                .contains(quote);
                                                                String key = "knowledge/"
                                                                                + source.id()
                                                                                + "/"
                                                                                + hit.path("chunkId").asString();
                                                                var anchor = input.path("citationAnchors")
                                                                                .valueStream()
                                                                                .filter(
                                                                                                a -> a.path("sourceKey")
                                                                                                                .asString()
                                                                                                                .equals(key)
                                                                                                                && a.path("content")
                                                                                                                                .asString()
                                                                                                                                .contains(quote))
                                                                                .findFirst()
                                                                                .orElseThrow();
                                                                assertThat(anchor.path("anchorId").asString())
                                                                                .isNotBlank();
                                                                assertThat(anchor.path("sourceVersion").asString())
                                                                                .isEqualTo(source.version() + "@"
                                                                                                + source.sha256());
                                                                assertThat(
                                                                                input.at("/untrustedEvidence/assessmentSources")
                                                                                                .valueStream())
                                                                                .anyMatch(
                                                                                                s -> s.path("sourceKey")
                                                                                                                .asString()
                                                                                                                .equals(key)
                                                                                                                && s.path("content")
                                                                                                                                .asString()
                                                                                                                                .contains(quote));
                                                                citation.put("sourceKey", key)
                                                                                .put("anchorId", anchor.path("anchorId")
                                                                                                .asString());
                                                                return phaseResult(objectMapper, request, output);
                                                        });

                        var body = Map.of(
                                        "requestId",
                                        UUID.randomUUID().toString(),
                                        "expectedTurn",
                                        1,
                                        "answer",
                                        fixture.path("answer").asString());
                        var accepted = performPost(
                                        "/api/interviews/sessions/" + session.sessionId() + "/answer-commands",
                                        body,
                                        token);
                        assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
                        String id = (String) dataMap(accepted).get("runId");
                        run(id);
                        var row = store.get(owner, id);
                        assertThat(row.status()).isEqualTo("SUCCEEDED");
                        assertThat(row.tools()).isOne();
                        assertThat(row.repairs()).isZero();
                        var evaluation = sessions.findById(owner, session.sessionId())
                                        .orElseThrow()
                                        .agent()
                                        .evaluations()
                                        .getFirst();
                        String excerpt = evaluation.knowledgeSources().getFirst().excerpts().getFirst().content();
                        assertThat(evaluation.assessment().detail().evidence())
                                        .anySatisfy(
                                                        e -> {
                                                                assertThat(e.sourceId())
                                                                                .isEqualTo(citation.path("sourceKey")
                                                                                                .asString());
                                                                assertThat(e.sourceVersion())
                                                                                .isEqualTo(source.version() + "@"
                                                                                                + source.sha256());
                                                                assertThat(e.quote()).isEqualTo(quote);
                                                                assertThat(excerpt.substring(e.offsetStart(),
                                                                                e.offsetEnd()))
                                                                                .isEqualTo(quote);
                                                        });
                }

                @Test
                void actualProtocolAssociationsReuseLogicalReceiptAcrossDifferentCallIds()
                                throws Exception {
                        var n = new AtomicInteger();
                        when(client.complete(any()))
                                        .thenAnswer(
                                                        inv -> {
                                                                ToolCallingChatClient.Request request = inv
                                                                                .getArgument(0);
                                                                if (n.getAndIncrement() == 0)
                                                                        return toolOutput(
                                                                                        "first",
                                                                                        "{\"sectionId\":\"resume-0\",\"maxChars\":2000}");
                                                                assertThat(
                                                                                request.messages().stream()
                                                                                                .filter(
                                                                                                                m -> m.path("role")
                                                                                                                                .asString()
                                                                                                                                .equals("tool")))
                                                                                .isNotEmpty();
                                                                assertThat(request.messages().toString())
                                                                                .contains("Redis",
                                                                                                "private fixture state");
                                                                return n.get() == 2
                                                                                ? toolOutput(
                                                                                                "second",
                                                                                                "{\"maxChars\":2000,\"sectionId\":\"resume-0\"}")
                                                                                : finalOutput(request);
                                                        });
                        String id = start();
                        run(id);
                        assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
                        verify(tools, times(1))
                                        .execute(
                                                        any(),
                                                        anyList(),
                                                        eq("read_material_section"),
                                                        any(),
                                                        anyList(),
                                                        any(),
                                                        anyList(),
                                                        anyLong());
                        assertThat(
                                        jdbc.queryForList(
                                                        "SELECT tool_call_id FROM interview_tool_receipts WHERE run_id=? ORDER BY id",
                                                        String.class,
                                                        id))
                                        .containsExactly("first", "second");
                        assertThat(store.get(owner, id).attempts()).isEqualTo(4);
                        assertThat(objectMapper.writeValueAsString(store.events(owner, id, 0)))
                                        .doesNotContain("Redis", "private fixture state");
                }

                @Test
                void adequateEvidenceUsesZeroTools() throws Exception {
                        when(client.complete(any())).thenAnswer(inv -> finalOutput(inv.getArgument(0)));
                        String id = start();
                        run(id);
                        assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
                        assertThat(store.get(owner, id).tools()).isZero();
                        verify(client, times(2)).complete(any());
                }

                @Test
                void forbiddenArgumentsRejectWholeBatchBeforeToolExecution() throws Exception {
                        when(client.complete(any()))
                                        .thenReturn(
                                                        toolOutput(
                                                                        "bad",
                                                                        "{\"sectionId\":\"resume-0\",\"maxChars\":2000,\"ownerId\":999}"));
                        String id = start();
                        run(id);
                        assertThat(store.get(owner, id).status()).isEqualTo("FAILED");
                        assertThat(store.get(owner, id).error()).isEqualTo("TOOL_ARGUMENTS");
                        verify(tools, never())
                                        .execute(
                                                        any(),
                                                        anyList(),
                                                        anyString(),
                                                        any(),
                                                        anyList(),
                                                        any(),
                                                        anyList(),
                                                        anyLong());
                }

                @Test
                void repairUsesPriorReceiptsAndToolsAreClosed() throws Exception {
                        var n = new AtomicInteger();
                        when(client.complete(any()))
                                        .thenAnswer(
                                                        inv -> {
                                                                ToolCallingChatClient.Request request = inv
                                                                                .getArgument(0);
                                                                int call = n.incrementAndGet();
                                                                if (call == 1)
                                                                        return toolOutput(
                                                                                        "read",
                                                                                        "{\"sectionId\":\"resume-0\",\"maxChars\":2000}");
                                                                if (actionPhase(request))
                                                                        return finalOutput(request);
                                                                if (call == 3)
                                                                        return new ToolCallingChatClient.Response(
                                                                                        objectMapper
                                                                                                        .createObjectNode()
                                                                                                        .put("role", "assistant")
                                                                                                        .put(
                                                                                                                        "content",
                                                                                                                        "{\"version\":\"phased-v2\",\"phase\":\"FINAL\",\"result\":{}}"),
                                                                                        "stop",
                                                                                        ModelUsage.unknown(
                                                                                                        ModelUsage.Status.MISSING));
                                                                assertThat(request.toolsAllowed()).isFalse();
                                                                assertThat(request.messages().toString())
                                                                                .contains("tool_call_id", "read");
                                                                return finalOutput(request);
                                                        });
                        String id = start();
                        run(id);
                        assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
                        assertThat(store.get(owner, id).repairs()).isEqualTo(1);
                        assertThat(store.get(owner, id).attempts()).isEqualTo(4);
                }

                @Test
                void savedResponseResumesValidationWithoutAnotherModelAttempt() throws Exception {
                        String id = start();
                        var claim = store.claim(store.get(owner, id));
                        var initial = objectMapper
                                        .createObjectNode()
                                        .put("stage", "MODEL")
                                        .put("outputPhase", "FINAL")
                                        .put("repaired", false)
                                        .put("actionRepaired", false);
                        initial.putArray("sources");
                        initial.putArray("messages")
                                        .addObject()
                                        .put("role", "system")
                                        .put("content", InterviewDecisionContract.ACTION_PROMPT);
                        store.checkpoint(claim, initial.toString(), "MODEL", null, null);
                        String attempt = store.reserveModelAttempt(claim, prepared("fixture")).id();
                        var cp = objectMapper
                                        .createObjectNode()
                                        .put("stage", "VALIDATE")
                                        .put("repaired", false)
                                        .put("outputPhase", "FINAL")
                                        .put("actionRepaired", false);
                        cp.putArray("sources");
                        cp.putArray("messages");
                        cp.set("response", finalOutput().message());
                        // 与生产 response() 检查点保持一致：已保存响应也是最后一条协议消息。
                        cp.withArray("messages").add(cp.path("response"));
                        store.response(claim, attempt, "{}", "{}", 1, cp.toString(), "VALIDATE");
                        jdbc.update(
                                        "UPDATE interview_runs SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
                                        id);
                        run(id);
                        assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
                        assertThat(store.get(owner, id).attempts()).isEqualTo(1);
                        verify(client, never()).complete(any());
                }

                @Test
                void missingKnowledgeExcerptsAreRetrievedOnceBeforeModelAndSurviveRetry() throws Exception {
                        String content = "Java 后端的核对依据：幂等键使用数据库唯一索引。";
                        var created = performPost(
                                        "/api/knowledge/documents",
                                        Map.of("title", "幂等", "content", content),
                                        token);
                        String docId = (String) dataMap(created).get("id");
                        var source = new cn.edu.whu.zhizhida.interview.api.KnowledgeSnapshot(
                                        docId,
                                        "幂等",
                                        "fixture",
                                        1,
                                        InterviewRunStore.sha(content),
                                        content,
                                        List.of());
                        var original = store.get(owner, start());
                        store.cancel(owner, original.id());
                        var input = (tools.jackson.databind.node.ObjectNode) objectMapper.readTree(original.input());
                        ((tools.jackson.databind.node.ObjectNode) input.at("/session/agent"))
                                        .set(
                                                        "training",
                                                        objectMapper.valueToTree(
                                                                        new cn.edu.whu.zhizhida.interview.api.InterviewTraining(
                                                                                        null, null, null,
                                                                                        List.of(source))));
                        String id = store.accept(
                                        owner,
                                        "START",
                                        UUID.randomUUID().toString(),
                                        "missing-excerpts",
                                        null,
                                        0,
                                        () -> new InterviewRunStore.Fixed(
                                                        original.sessionId(),
                                                        input.toString(),
                                                        original.config(),
                                                        "TOOL"))
                                        .id();

                        var calls = new AtomicInteger();
                        when(client.complete(any()))
                                        .thenAnswer(
                                                        inv -> {
                                                                ToolCallingChatClient.Request request = inv
                                                                                .getArgument(0);
                                                                assertThat(request.toolsAllowed())
                                                                                .isEqualTo(actionPhase(request));
                                                                assertThat(request.messages().toString())
                                                                                .contains("应用固定检索", "FOUND", content);
                                                                if (calls.incrementAndGet() == 1)
                                                                        throw cn.edu.whu.zhizhida.common.UpstreamServiceException
                                                                                        .coded(
                                                                                                        "MODEL_HTTP_503");
                                                                return finalOutput(request);
                                                        });
                        run(id);
                        assertThat(store.get(owner, id).phase()).isEqualTo("RETRY_WAIT");
                        jdbc.update(
                                        "UPDATE interview_runs SET not_before=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
                                        id);
                        run(id);
                        assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
                        assertThat(store.get(owner, id).batches()).isOne();
                        assertThat(store.get(owner, id).tools()).isOne();
                        assertThat(
                                        jdbc.queryForList(
                                                        "SELECT tool_call_id FROM interview_tool_receipts WHERE run_id=?",
                                                        String.class,
                                                        id))
                                        .containsExactly("fixed-search-1");
                }

                @Test
                void fixedEvidenceRouteUsesTheSameToolWithoutFabricatingModelToolCalls() throws Exception {
                        String initial = start();
                        var frozen = store.get(owner, initial);
                        store.cancel(owner, initial);
                        var run = store.accept(
                                        owner,
                                        "START",
                                        UUID.randomUUID().toString(),
                                        "fixed-fixture",
                                        null,
                                        0,
                                        () -> new InterviewRunStore.Fixed(
                                                        frozen.sessionId(),
                                                        frozen.input(),
                                                        frozen.config(),
                                                        "FIXED"));
                        when(client.complete(any()))
                                        .thenAnswer(
                                                        inv -> {
                                                                ToolCallingChatClient.Request request = inv
                                                                                .getArgument(0);
                                                                assertThat(request.tools()).isEmpty();
                                                                assertThat(request.toolsAllowed()).isFalse();
                                                                assertThat(request.messages().toString())
                                                                                .contains("EMPTY");
                                                                return finalOutput(request);
                                                        });
                        run(run.id());
                        assertThat(store.get(owner, run.id()).status()).isEqualTo("SUCCEEDED");
                        assertThat(store.get(owner, run.id()).tools()).isEqualTo(1);
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT tool_call_id FROM interview_tool_receipts WHERE run_id=?",
                                                        String.class,
                                                        run.id()))
                                        .isEqualTo("fixed-search-1");
                }

                @Test
                void transientProviderFailureKeepsAttemptChargeButAuthenticationDoesNotRetry()
                                throws Exception {
                        when(client.complete(any()))
                                        .thenThrow(
                                                        cn.edu.whu.zhizhida.common.UpstreamServiceException.coded(
                                                                        "MODEL_HTTP_503"))
                                        .thenAnswer(inv -> finalOutput(inv.getArgument(0)));
                        String id = start();
                        run(id);
                        assertThat(store.get(owner, id).status()).isEqualTo("ACCEPTED");
                        assertThat(store.get(owner, id).phase()).isEqualTo("RETRY_WAIT");
                        verify(client, times(1)).complete(any());
                        jdbc.update(
                                        "UPDATE interview_runs SET not_before=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
                                        id);
                        run(id);
                        assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
                        assertThat(store.get(owner, id).attempts()).isEqualTo(3);
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT status FROM interview_model_attempts WHERE run_id=? AND ordinal=1",
                                                        String.class,
                                                        id))
                                        .isEqualTo("FAILED");
                        doThrow(cn.edu.whu.zhizhida.common.UpstreamServiceException.coded("MODEL_HTTP_401"))
                                        .when(client)
                                        .complete(any());
                        id = start();
                        run(id);
                        assertThat(store.get(owner, id).error()).isEqualTo("MODEL_HTTP_401");
                        assertThat(store.get(owner, id).attempts()).isEqualTo(1);
                }

                @Test
                void secondInvalidFinalOutputStopsAfterOneRepairAndOneUserAdmission() throws Exception {
                        when(client.complete(any()))
                                        .thenAnswer(
                                                        inv -> actionPhase(inv.getArgument(0))
                                                                        ? finalOutput(inv.getArgument(0))
                                                                        : new ToolCallingChatClient.Response(
                                                                                        objectMapper
                                                                                                        .createObjectNode()
                                                                                                        .put("role", "assistant")
                                                                                                        .put("content", "{broken"),
                                                                                        "stop",
                                                                                        ModelUsage.unknown(
                                                                                                        ModelUsage.Status.MISSING)));
                        String id = start();
                        run(id);
                        assertThat(store.get(owner, id).error()).isEqualTo("MODEL_OUTPUT_INVALID");
                        assertThat(store.get(owner, id).attempts()).isEqualTo(3);
                        assertThat(store.get(owner, id).repairs()).isEqualTo(1);
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT used_units FROM request_quotas WHERE scope_key=?",
                                                        Integer.class,
                                                        InterviewRunStore.sha("ai-day:" + owner)))
                                        .isEqualTo(3);
                }

                @Test
                void invalidFieldLogsConnectRunAndAttemptsWithoutEchoingModelOrCredentials()
                                throws Exception {
                        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                                        .getLogger(InterviewRunWorker.class);
                        var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
                        captured.start();
                        logger.addAppender(captured);
                        try {
                                String privateText = "PRIVATE_MODEL_CONTENT_DO_NOT_LOG";
                                when(client.complete(any()))
                                                .thenAnswer(
                                                                inv -> phaseResult(
                                                                                objectMapper,
                                                                                inv.getArgument(0),
                                                                                objectMapper
                                                                                                .createObjectNode()
                                                                                                .put("private", privateText)));
                                String id = start();
                                run(id);
                                assertThat(store.get(owner, id).error()).isEqualTo("MODEL_OUTPUT_INVALID");
                                var attempts = jdbc.queryForList(
                                                "SELECT attempt_id FROM interview_model_attempts WHERE run_id=? ORDER BY ordinal",
                                                String.class,
                                                id);
                                var messages = captured.list.stream()
                                                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                                                .toList();
                                assertThat(messages)
                                                .anyMatch(
                                                                message -> message.contains(id)
                                                                                && message.contains("MISSING_FIELD")
                                                                                && message.contains("question")
                                                                                && message.contains("VALIDATE"));
                                assertThat(String.join("\n", messages))
                                                .contains(attempts.toArray(String[]::new))
                                                .doesNotContain(privateText, token, "项目使用 Redis 保存幂等键");
                                assertThat(captured.list).allMatch(event -> event.getThrowableProxy() == null);
                        } finally {
                                logger.detachAppender(captured);
                                captured.stop();
                        }
                }

                @Test
                void repairCannotReopenToolsEvenIfProviderAsksForThem() throws Exception {
                        var finals = new AtomicInteger();
                        when(client.complete(any()))
                                        .thenAnswer(
                                                        inv -> {
                                                                ToolCallingChatClient.Request request = inv
                                                                                .getArgument(0);
                                                                if (actionPhase(request))
                                                                        return finalOutput(request);
                                                                return finals.getAndIncrement() == 0
                                                                                ? phaseResult(
                                                                                                objectMapper,
                                                                                                request,
                                                                                                objectMapper.createObjectNode())
                                                                                : toolOutput(
                                                                                                "forbidden-repair",
                                                                                                "{\"sectionId\":\"resume-0\",\"maxChars\":10}");
                                                        });
                        String id = start();
                        run(id);
                        assertThat(store.get(owner, id).error()).isEqualTo("TOOL_DISABLED");
                        assertThat(store.get(owner, id).tools()).isZero();
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT COUNT(*) FROM interview_tool_receipts WHERE run_id=?",
                                                        Integer.class,
                                                        id))
                                        .isZero();
                }
        }

        /** 以脚本控制的聊天模型验证真实 MCP HTTP、LangChain4j、注册表到持久回执的完整链路。 */
        @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                        "interview.runs.worker-enabled=false",
                        "ai.provider=deepseek",
                        "interview.runs.loop-policy=A1",
                        "agent.mcp.reference.enabled=true"
        })
        @Nested
        class Mcp extends ToolScenario {
                static final McpReferenceFixture MCP = new McpReferenceFixture();

                @DynamicPropertySource
                static void endpoint(DynamicPropertyRegistry registry) {
                        registry.add("agent.mcp.reference.url", MCP::url);
                }

                @AfterAll
                static void stopFixture() {
                        MCP.close();
                }

                @Test
                void repeatedMcpRequestsUsePersistedReceiptWithoutRepeatingRemoteExecution()
                                throws Exception {
                        MCP.calls.set(0);
                        var count = new AtomicInteger();
                        when(client.complete(any()))
                                        .thenAnswer(
                                                        invocation -> {
                                                                ToolCallingChatClient.Request request = invocation
                                                                                .getArgument(0);
                                                                int n = count.incrementAndGet();
                                                                if (n >= 3)
                                                                        return finalOutput(request);
                                                                if (n == 2)
                                                                        assertThat(request.messages().toString())
                                                                                        .contains("合成缓存参考",
                                                                                                        "UNTRUSTED_REFERENCE");
                                                                var message = objectMapper.createObjectNode()
                                                                                .put("role", "assistant");
                                                                message.putArray("tool_calls")
                                                                                .addObject()
                                                                                .put("id", "mcp-" + n)
                                                                                .put("type", "function")
                                                                                .putObject("function")
                                                                                .put("name", "search_reference")
                                                                                .put("arguments",
                                                                                                "{\"query\":\"cache\",\"topK\":1}");
                                                                return new ToolCallingChatClient.Response(
                                                                                message, "tool_calls",
                                                                                finalOutput().usage());
                                                        });
                        String id = start();
                        run(id);
                        assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
                        assertThat(MCP.calls).hasValue(1);
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT COUNT(*) FROM interview_tool_receipts WHERE run_id=?",
                                                        Integer.class,
                                                        id))
                                        .isEqualTo(2);
                        assertThat(store.get(owner, id).attempts()).isEqualTo(4);
                }
        }

        /** 真实 HTTP 适配器、Worker 与已提交的 MySQL 调用记录共用本地供应商替身。 */
        @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                        "interview.runs.worker-enabled=false", "ai.provider=deepseek",
                        "ai.api-key=synthetic-only",
                        "ai.resilience.failure-threshold=100", "interview.runs.decision-protocol=phased-v2",
                        "interview.runs.loop-policy=A1",
                        "management.tracing.sampling.probability=1",
                        "management.tracing.export.otlp.enabled=false"
        })
        @org.springframework.context.annotation.Import(InterviewStreamingTracingIT.Tracing.TraceConfig.class)
        @Nested
        class ModelTransport extends InterviewRunScenario {
                static final HttpServer SERVER = server();
                static final List<String> SENT = new CopyOnWriteArrayList<>();
                static final List<Boolean> PRECOMMITTED = new CopyOnWriteArrayList<>();
                static final List<tools.jackson.databind.JsonNode> INPUTS = new CopyOnWriteArrayList<>();
                static volatile JdbcTemplate database;
                static volatile int status;
                static volatile int failureAt;
                static volatile boolean incomplete;
                static volatile boolean stalled;
                static volatile java.util.concurrent.CountDownLatch responseRelease;
                static volatile boolean invalidUsage;
                static volatile boolean repeatTool;
                @Autowired
                DeepSeekToolChatClient client;
                @Autowired
                JdbcTemplate jdbc;
                @Autowired
                InterviewRunModel model;
                @Autowired
                org.springframework.context.ApplicationContext application;
                @Autowired
                InterviewStreamingTracingIT.Tracing.Collector traces;
                @Autowired
                io.opentelemetry.sdk.trace.SdkTracerProvider tracer;

                static final String FINAL = "{\"version\":\"phased-v2\",\"phase\":\"FINAL\",\"result\":{\"question\":\"请说明你在相关经历中承担的个人职责？\",\"questionCompetencyId\":\"experience\"}}";

                static HttpServer server() {
                        try {
                                var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                                server.createContext(
                                                "/chat/completions",
                                                exchange -> {
                                                        var json = new JsonMapper();
                                                        var input = json.readTree(
                                                                        exchange.getRequestBody().readAllBytes());
                                                        INPUTS.add(input);
                                                        String attempt = exchange.getRequestHeaders()
                                                                        .getFirst("X-Client-Request-Id");
                                                        SENT.add(attempt);
                                                        PRECOMMITTED.add(
                                                                        database.queryForObject(
                                                                                        "SELECT COUNT(*) FROM interview_model_attempts WHERE attempt_id=? AND status='STARTED'",
                                                                                        Integer.class,
                                                                                        attempt) == 1);
                                                        var message = json.createObjectNode()
                                                                        .put("role", "assistant")
                                                                        .put("provider_extension", "opaque-fixture");
                                                        String finish = "stop";
                                                        if (input.at("/messages/0/content").asString()
                                                                        .contains("FINAL"))
                                                                message.put("content", FINAL);
                                                        else if (SENT.size() == 1 || repeatTool && SENT.size() == 2) {
                                                                finish = "tool_calls";
                                                                message.putNull("content")
                                                                                .putArray("tool_calls")
                                                                                .addObject()
                                                                                .put("id", "read-" + SENT.size())
                                                                                .put("type", "function")
                                                                                .putObject("function")
                                                                                .put("name", "read_material_section")
                                                                                .put(
                                                                                                "arguments",
                                                                                                "{\"sectionId\":\"resume-0\",\"maxChars\":2000}");
                                                        } else
                                                                message.put(
                                                                                "content",
                                                                                "{\"version\":\"phased-v2\",\"phase\":\"ACTION\",\"action\":\"COMPLETE\",\"reason\":\"资料充分\"}");
                                                        var root = json.createObjectNode();
                                                        var choice = root.putArray("choices")
                                                                        .addObject()
                                                                        .put("index", 0)
                                                                        .put("finish_reason", finish);
                                                        var usage = root.putObject("usage")
                                                                        .put("completion_tokens", 5)
                                                                        .put("total_tokens", 15);
                                                        if (invalidUsage)
                                                                usage.put("prompt_tokens", "10");
                                                        else
                                                                usage.put("prompt_tokens", 10);
                                                        choice.set("message", message);
                                                        String body = root.toString();
                                                        exchange.getResponseHeaders().set("Content-Type",
                                                                        "application/json");
                                                        exchange.getResponseHeaders().set("Location",
                                                                        "/chat/completions");
                                                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                                                        exchange.sendResponseHeaders(
                                                                        failureAt == 0 || SENT.size() == failureAt
                                                                                        ? status
                                                                                        : 200,
                                                                        bytes.length);
                                                        try {
                                                                if (incomplete || stalled) {
                                                                        exchange.getResponseBody().write(bytes, 0,
                                                                                        bytes.length / 2);
                                                                        exchange.getResponseBody().flush();
                                                                        if (stalled)
                                                                                responseRelease.await(
                                                                                                10,
                                                                                                java.util.concurrent.TimeUnit.SECONDS);
                                                                } else
                                                                        exchange.getResponseBody().write(bytes);
                                                        } catch (InterruptedException ex) {
                                                                Thread.currentThread().interrupt();
                                                        } finally {
                                                                exchange.close();
                                                        }
                                                });
                                server.start();
                                return server;
                        } catch (java.io.IOException ex) {
                                throw new ExceptionInInitializerError(ex);
                        }
                }

                @DynamicPropertySource
                static void endpoint(DynamicPropertyRegistry registry) {
                        registry.add(
                                        "ai.deepseek.base-url",
                                        () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
                }

                @AfterAll
                static void closeServer() {
                        SERVER.stop(0);
                }

                @BeforeEach
                void setup() throws Exception {
                        database = jdbc;
                        SENT.clear();
                        PRECOMMITTED.clear();
                        INPUTS.clear();
                        status = 200;
                        failureAt = 0;
                        incomplete = false;
                        stalled = false;
                        invalidUsage = false;
                        repeatTool = false;
                        responseRelease = new java.util.concurrent.CountDownLatch(1);
                }

                String start() throws Exception {
                        return start("使用 Redis 保存幂等键。");
                }

                String start(String summary) throws Exception {
                        return acceptStart(
                                        Map.of("targetRole", "Java 后端", "resumeSummary", summary, "totalRounds", 3));
                }

                @Test
                void configuredSdkIsFrozenAndCannotResumeAnotherEndpoint() throws Exception {
                        assertThat(client.configuration().protocolVersion())
                                        .isEqualTo("deepseek-langchain4j-v1");
                        assertThat(application.getBeansOfType(tools.jackson.databind.ObjectMapper.class))
                                        .hasSize(1);
                        // MCP 的 ToolExecutionResult 来自此 SDK；类在 classpath 中存在不代表启用了 AiServices 编排。
                        assertThat(
                                        application.getBeansOfType(
                                                        Class.forName("dev.langchain4j.service.AiServices")))
                                        .isEmpty();
                        String id = start();
                        String saved = store.get(owner, id).config();
                        model.requireConfiguration(saved);
                        var otherClient = new DeepSeekToolChatClient(
                                        "synthetic-only",
                                        "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/other",
                                        client.configuration().model(),
                                        client.configuration().thinking(),
                                        objectMapper,
                                        cn.edu.whu.zhizhida.ai.AiGuardFixtures.guard(),
                                        cn.edu.whu.zhizhida.observability.BusinessMetrics.disabled());
                        var otherConfig = (tools.jackson.databind.node.ObjectNode) objectMapper.readTree(saved);
                        otherConfig.set("model", objectMapper.valueToTree(otherClient.configuration()));
                        otherConfig.set(
                                        "executionProfile", objectMapper.valueToTree(otherClient.executionProfile()));
                        assertThatThrownBy(() -> model.requireConfiguration(otherConfig.toString()))
                                        .hasMessage("MODEL_CONFIG_CHANGED");
                        assertThat(SENT).isEmpty();
                }

                @Test
                void outboundProjectionIsMeasuredAndHashedWhileFullReceiptsAndCheckpointSurvive()
                                throws Exception {
                        repeatTool = true;
                        String id = start("使用 Redis 保存幂等键。".repeat(100));
                        run(id);
                        assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
                        assertThat(SENT).hasSize(4);
                        reconcile(id);
                        var attempts = jdbc.queryForList(
                                        "SELECT request_hash,context_version,input_chars,tool_chars,omitted_tool_results FROM interview_model_attempts WHERE run_id=? ORDER BY ordinal",
                                        id);
                        for (int i = 0; i < INPUTS.size(); i++) {
                                var input = INPUTS.get(i);
                                var envelope = objectMapper
                                                .createObjectNode()
                                                .put(
                                                                "toolsAllowed",
                                                                input.path("tool_choice").asString().equals("auto"));
                                envelope.set("messages", input.path("messages"));
                                envelope.set(
                                                "tools",
                                                input.has("tools") ? input.path("tools")
                                                                : objectMapper.createArrayNode());
                                assertThat(attempts.get(i).get("request_hash"))
                                                .isEqualTo(
                                                                InterviewRunStore.sha(
                                                                                store.get(owner, id).config() + "\n"
                                                                                                + envelope));
                                assertThat(attempts.get(i).get("context_version")).isEqualTo("context-window-v1");
                                assertThat(((Number) attempts.get(i).get("input_chars")).intValue())
                                                .isEqualTo(envelope.toString().length());
                        }
                        assertThat(((Number) attempts.get(3).get("omitted_tool_results")).intValue())
                                        .isEqualTo(1);
                        var outbound = INPUTS.get(3).path("messages");
                        assertThat(outbound.toString()).contains("CONTEXT_OMITTED", "opaque-fixture");
                        String checkpoint = store.get(owner, id).checkpoint();
                        assertThat(checkpoint).doesNotContain("CONTEXT_OMITTED");
                        assertThat(
                                        jdbc.queryForList(
                                                        "SELECT result_json FROM interview_tool_receipts WHERE run_id=?",
                                                        String.class,
                                                        id))
                                        .hasSize(2)
                                        .allSatisfy(
                                                        raw -> assertThat(raw)
                                                                        .contains("Redis")
                                                                        .doesNotContain("CONTEXT_OMITTED"));
                }

                @Test
                void oversizedRestoredContextFailsBeforeAttemptOrAdditionalQuota() throws Exception {
                        String id = start();
                        var claim = store.claim(store.get(owner, id));
                        prepareModelCheckpoint(claim, "FINAL");
                        var cp = (tools.jackson.databind.node.ObjectNode) objectMapper
                                        .readTree(store.get(owner, id).checkpoint());
                        cp.withArray("messages")
                                        .addObject()
                                        .put("role", "user")
                                        .put("content", "x".repeat(160_000));
                        store.checkpoint(claim, cp.toString(), "MODEL", null, null);
                        var quotas = jdbc.queryForList(
                                        "SELECT scope_key,used_units FROM request_quotas ORDER BY scope_key");
                        worker.execute(claim);
                        assertThat(store.get(owner, id).error()).isEqualTo("RUN_CONTEXT_BUDGET");
                        assertThat(store.get(owner, id).attempts()).isZero();
                        assertThat(SENT).isEmpty();
                        assertThat(
                                        jdbc.queryForList(
                                                        "SELECT scope_key,used_units FROM request_quotas ORDER BY scope_key"))
                                        .isEqualTo(quotas);
                }

                void reconcile(String id) {
                        var attempts = jdbc.queryForList(
                                        "SELECT attempt_id FROM interview_model_attempts WHERE run_id=? ORDER BY ordinal",
                                        String.class,
                                        id);
                        assertThat(SENT).doesNotHaveDuplicates().containsExactlyElementsOf(attempts);
                        assertThat(PRECOMMITTED).hasSize(SENT.size()).containsOnly(true);
                        assertThat(store.get(owner, id).attempts()).isEqualTo(SENT.size());
                }

                @Test
                void toolsAndFinalUseSeparateCommittedAttemptsAndPreserveRawContinuation()
                                throws Exception {
                        String id = start();
                        run(id);
                        assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
                        assertThat(SENT).hasSize(3);
                        reconcile(id);
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT COUNT(*) FROM interview_tool_receipts WHERE run_id=?",
                                                        Integer.class,
                                                        id))
                                        .isEqualTo(1);
                        assertThat(
                                        jdbc.queryForList(
                                                        "SELECT response_json FROM interview_model_attempts WHERE run_id=?",
                                                        String.class,
                                                        id))
                                        .allSatisfy(value -> assertThat(value).contains("opaque-fixture"));
                        tracer.forceFlush().join(3, java.util.concurrent.TimeUnit.SECONDS);
                        var recorded = traces.spans.stream()
                                        .filter(
                                                        span -> id.equals(
                                                                        InterviewStreamingTracingIT.Tracing.tag(
                                                                                        span, "agent.run.id")))
                                        .toList();
                        var runSpan = recorded.stream()
                                        .filter(span -> span.getName().equals("agent.run"))
                                        .findFirst()
                                        .orElseThrow();
                        var modelSpans = recorded.stream().filter(span -> span.getName().equals("agent.model"))
                                        .toList();
                        assertThat(modelSpans)
                                        .hasSize(3)
                                        .allSatisfy(
                                                        span -> {
                                                                assertThat(span.getTraceId())
                                                                                .isEqualTo(runSpan.getTraceId());
                                                                assertThat(span.getParentSpanId())
                                                                                .isEqualTo(runSpan.getSpanId());
                                                        });
                        assertThat(modelSpans)
                                        .extracting(
                                                        span -> InterviewStreamingTracingIT.Tracing.tag(
                                                                        span, "agent.attempt.id"))
                                        .containsExactlyInAnyOrderElementsOf(SENT);
                }

                @ParameterizedTest
                @ValueSource(ints = { 429, 500, 502, 503, 307 })
                void everyExplicitRetryHasItsOwnReservationAndRedirectsAreNotFollowed(int code)
                                throws Exception {
                        status = code;
                        String id = start();
                        run(id);
                        for (int i = 0; i < 4 && !store.get(owner, id).terminal(); i++) {
                                assertThat(store.get(owner, id).phase()).isEqualTo("RETRY_WAIT");
                                makeRetryDue(id);
                                run(id);
                        }
                        assertThat(store.get(owner, id).status()).isEqualTo("FAILED");
                        assertThat(store.get(owner, id).error()).isEqualTo("MODEL_HTTP_" + code);
                        assertThat(SENT).isNotEmpty();
                        reconcile(id);
                }

                void makeRetryDue(String id) {
                        // 仅在可丢弃数据库中提前重试时间，避免等待且不改变生产预算。
                        jdbc.update(
                                        "UPDATE interview_runs SET not_before=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)),"
                                                        + "next_dispatch_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
                                        id);
                }

                @Test
                void transientFailureYieldsWithoutNewHttpUntilDueAndKeepsTheSameCommand() throws Exception {
                        status = 429;
                        String id = start();
                        var before = store.get(owner, id);
                        var claim = store.claim(before);
                        worker.execute(claim);
                        assertThat(SENT).hasSize(1);
                        assertThat(store.get(owner, id).status()).isEqualTo("ACCEPTED");
                        assertThat(store.get(owner, id).phase()).isEqualTo("RETRY_WAIT");
                        assertThat(store.retryScheduled(claim)).isTrue();
                        assertThat(store.heartbeat(claim)).isFalse();
                        jdbc.update(
                                        "UPDATE interview_runs SET not_before=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)) WHERE id=?",
                                        id);
                        assertThat(store.candidates()).extracting(InterviewRunStore.Row::id).doesNotContain(id);
                        for (int i = 0; i < 10; i++)
                                assertThat(store.claim(before)).isNull();
                        assertThatThrownBy(() -> store.reserveModelAttempt(claim, prepared("late-worker")).id())
                                        .hasMessage("RUN_FENCED");
                        assertThat(store.events(owner, id, 0))
                                        .anySatisfy(
                                                        event -> {
                                                                assertThat(event.type())
                                                                                .isEqualTo("model.retry_scheduled");
                                                                assertThat(event.message())
                                                                                .doesNotContain(
                                                                                                "synthetic-only",
                                                                                                "Redis",
                                                                                                "opaque-fixture");
                                                        });
                        status = 200;
                        makeRetryDue(id);
                        run(id);
                        assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
                        assertThat(store.get(owner, id).deadline()).isEqualTo(before.deadline());
                        assertThat(store.get(owner, id).requestId()).isEqualTo(before.requestId());
                        assertThat(SENT).hasSize(3);
                        reconcile(id);
                }

                @Test
                void retryResumesSavedToolReceiptAndRetainsFinalReservation() throws Exception {
                        status = 503;
                        failureAt = 2;
                        String id = start();
                        run(id);
                        assertThat(SENT).hasSize(2);
                        assertThat(store.get(owner, id).phase()).isEqualTo("RETRY_WAIT");
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT COUNT(*) FROM interview_tool_receipts WHERE run_id=?",
                                                        Integer.class,
                                                        id))
                                        .isEqualTo(1);
                        String checkpoint = store.get(owner, id).checkpoint();
                        makeRetryDue(id);
                        run(id);
                        assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT COUNT(*) FROM interview_tool_receipts WHERE run_id=?",
                                                        Integer.class,
                                                        id))
                                        .isEqualTo(1);
                        assertThat(checkpoint).contains("read-1", "opaque-fixture");
                        assertThat(SENT).hasSize(4);
                        reconcile(id);
                }

                @Test
                void cancellationDuringRetryNeverSendsAnotherRequest() throws Exception {
                        status = 503;
                        String id = start();
                        run(id);
                        var stale = store.get(owner, id);
                        store.cancel(owner, id);
                        makeRetryDue(id);
                        assertThat(store.claim(stale)).isNull();
                        store.recoverDispatches();
                        assertThat(store.get(owner, id).status()).isEqualTo("CANCELLED");
                        assertThat(SENT).hasSize(1);
                        reconcile(id);
                }

                @Test
                void databaseClockMakesRetryEligibleWithoutAnInMemoryTimer() throws Exception {
                        status = 429;
                        String id = start();
                        run(id);
                        assertThat(SENT).hasSize(1);
                        status = 200;
                        org.awaitility.Awaitility.await()
                                        .atMost(Duration.ofSeconds(5))
                                        .untilAsserted(
                                                        () -> assertThat(store.candidates())
                                                                        .extracting(InterviewRunStore.Row::id)
                                                                        .contains(id));
                        // 其他执行者只依靠持久化记录即可恢复，无需复用原执行者的内存状态。
                        run(id);
                        assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
                        reconcile(id);
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT SUM(used_units) FROM request_quotas WHERE scope_key=?",
                                                        Integer.class,
                                                        InterviewRunStore.sha("ai-day:" + owner)))
                                        .isEqualTo(3);
                }

                @Test
                void invalidUsageStaysInvalidInTheDurableLedger() throws Exception {
                        invalidUsage = true;
                        String id = start();
                        run(id);
                        reconcile(id);
                        assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
                        assertThat(
                                        jdbc.queryForList(
                                                        "SELECT usage_json FROM interview_model_attempts WHERE run_id=?",
                                                        String.class,
                                                        id))
                                        .allSatisfy(
                                                        value -> assertThat(value)
                                                                        .contains("INVALID")
                                                                        .doesNotContain("REPORTED"));
                }

                @Test
                void reservedButUnsentAttemptAndExpiredFenceNeverReachHttp() throws Exception {
                        String id = start();
                        var claim = store.claim(store.get(owner, id));
                        prepareModelCheckpoint(claim, "ACTION");
                        store.reserveModelAttempt(claim, prepared("synthetic-pre-send")).id();
                        assertThat(SENT).isEmpty();
                        jdbc.update(
                                        "UPDATE interview_runs SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
                                        id);
                        assertThatThrownBy(() -> store.reserveModelAttempt(claim, prepared("expired")).id())
                                        .hasMessage("RUN_FENCED");
                        assertThat(store.get(owner, id).attempts()).isEqualTo(1);
                        store.claim(store.get(owner, id));
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT status FROM interview_model_attempts WHERE run_id=?",
                                                        String.class,
                                                        id))
                                        .isEqualTo("UNKNOWN");
                        assertThat(SENT).isEmpty(); // 保守预占只记录可能的消耗，不证明实际已发送请求。
                }

                @Test
                void savedJsonFinalResumesWithoutAnotherHttpRequest() throws Exception {
                        String id = start();
                        var claim = store.claim(store.get(owner, id));
                        prepareModelCheckpoint(claim, "FINAL");
                        String attempt = store.reserveModelAttempt(claim, prepared("json-fixture")).id();
                        var reply = client.complete(
                                        new ToolCallingChatClient.Request(
                                                        client.configuration(),
                                                        List.of(
                                                                        objectMapper
                                                                                        .createObjectNode()
                                                                                        .put("role", "user")
                                                                                        .put("content", "FINAL fixture")),
                                                        List.of(),
                                                        false,
                                                        Duration.ofSeconds(2),
                                                        attempt));
                        var cp = objectMapper
                                        .createObjectNode()
                                        .put("stage", "VALIDATE")
                                        .put("outputPhase", "FINAL")
                                        .put("repaired", false)
                                        .put("actionRepaired", false);
                        cp.putArray("sources");
                        cp.putArray("messages").add(reply.message());
                        cp.set("response", reply.message());
                        store.response(
                                        claim,
                                        attempt,
                                        objectMapper.writeValueAsString(reply),
                                        objectMapper.writeValueAsString(reply.usage()),
                                        1,
                                        cp.toString(),
                                        "VALIDATE");
                        jdbc.update(
                                        "UPDATE interview_runs SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
                                        id);
                        run(id);
                        assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
                        assertThat(SENT).hasSize(1);
                        reconcile(id);
                }

                @ParameterizedTest
                @ValueSource(booleans = { false, true })
                void truncatedOrTimedOutFinalResponseCannotCreateAResult(boolean timeout) throws Exception {
                        incomplete = !timeout;
                        stalled = timeout;
                        String id = start();
                        var claim = store.claim(store.get(owner, id));
                        prepareModelCheckpoint(claim, "FINAL");
                        // 保留 25 秒修正和 5 秒提交预算，实际 HTTP 调用仅能使用余下约 3 秒。
                        jdbc.update(
                                        "UPDATE interview_runs SET deadline=TIMESTAMPADD(SECOND,33,CURRENT_TIMESTAMP(6)) WHERE id=?",
                                        id);
                        try {
                                worker.execute(claim);
                                assertThat(store.get(owner, id).status()).isEqualTo("FAILED");
                                assertThat(store.get(owner, id).error()).isEqualTo("MODEL_NETWORK");
                                assertThat(
                                                jdbc.queryForList(
                                                                "SELECT status FROM interview_model_attempts WHERE run_id=?",
                                                                String.class,
                                                                id))
                                                .containsExactly("UNKNOWN");
                                assertThat(store.get(owner, id).view().sessionId()).isNull();
                                assertThat(SENT).hasSize(1);
                                reconcile(id);
                        } finally {
                                responseRelease.countDown();
                        }
                }

                private void prepareModelCheckpoint(InterviewRunStore.Claim claim, String phase) {
                        var cp = objectMapper
                                        .createObjectNode()
                                        .put("stage", "MODEL")
                                        .put("outputPhase", phase)
                                        .put("repaired", false)
                                        .put("actionRepaired", false);
                        cp.putArray("sources");
                        cp.putArray("messages")
                                        .addObject()
                                        .put("role", "system")
                                        .put("content", phase + " fixture");
                        store.checkpoint(claim, cp.toString(), "MODEL", null, null);
                }
        }

        @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                        "interview.runs.worker-enabled=false", "ai.provider=deepseek" })
        @org.springframework.context.annotation.Import(ToolLoop.ExtensionTools.class)
        @Nested
        class ToolLoop extends InterviewRunScenario {
                @MockitoBean
                ToolCallingChatClient client;
                @MockitoSpyBean
                InterviewEvidenceTools tools;
                @Autowired
                InterviewRunModel model;
                @Autowired
                JdbcTemplate jdbc;
                static volatile String fixtureMode = "normal";
                static volatile java.util.concurrent.CountDownLatch fixtureEntered;
                static volatile java.util.concurrent.CountDownLatch fixtureRelease;
                static volatile Thread fixtureThread;

                @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
                static class ExtensionTools {
                        @org.springframework.context.annotation.Bean
                        InterviewToolProvider fixtureTool(tools.jackson.databind.ObjectMapper json) {
                                return () -> java.util.List.of(
                                                new cn.edu.whu.zhizhida.common.execution.ToolRegistry.Registration<>(
                                                                json.readTree(
                                                                                """
                                                                                                {"type":"function","function":{"name":"fixture_reference","description":"Synthetic reference fixture",
                                                                                                 "parameters":{"type":"object","properties":{},"additionalProperties":false}}}
                                                                                                """),
                                                                "fixture-v1",
                                                                java.util.Set.of(),
                                                                new cn.edu.whu.zhizhida.common.execution.ToolRegistry.Limits(
                                                                                1000, 256),
                                                                (context, args) -> {
                                                                        if (args == null || !args.isObject()
                                                                                        || !args.isEmpty())
                                                                                throw new cn.edu.whu.zhizhida.common.execution.RunFailureException(
                                                                                                "TOOL_ARGUMENTS");
                                                                },
                                                                (context, args) -> {
                                                                        if (fixtureMode.equals("slow")) {
                                                                                fixtureThread = Thread.currentThread();
                                                                                fixtureEntered.countDown();
                                                                                boolean waiting = true;
                                                                                while (waiting) {
                                                                                        try {
                                                                                                fixtureRelease.await();
                                                                                                waiting = false;
                                                                                        } catch (InterruptedException ignored) {
                                                                                        }
                                                                                }
                                                                        }
                                                                        return new InterviewEvidenceTools.Result(
                                                                                        json.createObjectNode()
                                                                                                        .put("status", "EMPTY")
                                                                                                        .put(
                                                                                                                        "sourceVersion",
                                                                                                                        fixtureMode.equals(
                                                                                                                                        "large")
                                                                                                                                                        ? "private".repeat(
                                                                                                                                                                        100)
                                                                                                                                                        : "fixture-v1"),
                                                                                        java.util.List.of());
                                                                }));
                        }
                }

                @Test
                void registeredExtensionUsesTheExistingLoopBudgetAndDurableReceipt() throws Exception {
                        var requests = new AtomicInteger();
                        when(client.complete(any()))
                                        .thenAnswer(
                                                        inv -> {
                                                                ToolCallingChatClient.Request request = inv
                                                                                .getArgument(0);
                                                                if (request.toolsAllowed())
                                                                        assertThat(request.tools())
                                                                                        .anyMatch(
                                                                                                        t -> t.path("function")
                                                                                                                        .path("name")
                                                                                                                        .asString()
                                                                                                                        .equals("fixture_reference"));
                                                                if (requests.getAndIncrement() == 0)
                                                                        return call("extension-1", "fixture_reference",
                                                                                        "{}");
                                                                assertThat(request.messages().toString())
                                                                                .contains("fixture-v1", "extension-1",
                                                                                                "EMPTY");
                                                                return output(request);
                                                        });
                        String id = start("B0", false);
                        run(id);
                        assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
                        assertThat(store.get(owner, id).attempts()).isEqualTo(3);
                        assertThat(store.get(owner, id).batches()).isEqualTo(1);
                        assertThat(store.get(owner, id).tools()).isEqualTo(1);
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT COUNT(*) FROM interview_tool_receipts WHERE run_id=? AND tool_name=?",
                                                        Integer.class,
                                                        id,
                                                        "fixture_reference"))
                                        .isEqualTo(1);
                        verify(tools, times(1))
                                        .execute(
                                                        any(),
                                                        anyList(),
                                                        eq("fixture_reference"),
                                                        any(),
                                                        anyList(),
                                                        any(),
                                                        anyList(),
                                                        anyLong());
                }

                @Test
                void invalidLaterToolPreventsTheWholeRegisteredBatchFromExecuting() throws Exception {
                        var batch = call("extension-1", "fixture_reference", "{}");
                        ((tools.jackson.databind.node.ArrayNode) batch.message().path("tool_calls"))
                                        .add(
                                                        call("unregistered-2", "unregistered_shell", "{}")
                                                                        .message()
                                                                        .path("tool_calls")
                                                                        .get(0));
                        when(client.complete(any())).thenReturn(batch);
                        String id = start("B0", false);
                        run(id);
                        assertThat(store.get(owner, id).error()).isEqualTo("TOOL_DENIED");
                        assertThat(store.get(owner, id).batches()).isZero();
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT COUNT(*) FROM interview_tool_receipts WHERE run_id=?",
                                                        Integer.class,
                                                        id))
                                        .isZero();
                        verify(tools, never())
                                        .execute(
                                                        any(),
                                                        anyList(),
                                                        anyString(),
                                                        any(),
                                                        anyList(),
                                                        any(),
                                                        anyList(),
                                                        anyLong());
                }

                @BeforeEach
                void setup() throws Exception {
                        fixtureMode = "normal";
                        fixtureEntered = new java.util.concurrent.CountDownLatch(1);
                        fixtureRelease = new java.util.concurrent.CountDownLatch(1);
                        fixtureThread = null;
                        when(client.configuration())
                                        .thenReturn(
                                                        new ToolCallingChatClient.Config(
                                                                        "controlled-fixture", "enabled", "fixture-v1"));
                }

                @AfterEach
                void cleanup() throws Exception {
                        fixtureRelease.countDown();
                        if (fixtureThread != null)
                                fixtureThread.join(2000);
                }

                @Test
                void timeoutKeepsReservedBatchButNeverWritesLateReceiptOrCallsModelAgain()
                                throws Exception {
                        fixtureMode = "slow";
                        when(client.complete(any())).thenReturn(call("slow", "fixture_reference", "{}"));
                        String id = start("B0", false);
                        run(id);
                        assertThat(store.get(owner, id).error()).isEqualTo("TOOL_TIMEOUT");
                        assertThat(store.get(owner, id).tools()).isEqualTo(1);
                        fixtureRelease.countDown();
                        assertThat(fixtureThread).isNotNull();
                        fixtureThread.join(2000);
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT COUNT(*) FROM interview_tool_receipts WHERE run_id=?",
                                                        Integer.class,
                                                        id))
                                        .isZero();
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT COUNT(*) FROM interview_sessions WHERE session_id=?",
                                                        Integer.class,
                                                        id))
                                        .isZero();
                        verify(client, times(1)).complete(any());
                }

                @Test
                void oversizedPayloadFailsBeforeReceiptAndDoesNotLeakIntoPublicEvents() throws Exception {
                        fixtureMode = "large";
                        when(client.complete(any())).thenReturn(call("large", "fixture_reference", "{}"));
                        String id = start("B0", false);
                        run(id);
                        assertThat(store.get(owner, id).error()).isEqualTo("TOOL_RESULT_BUDGET");
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT COUNT(*) FROM interview_tool_receipts WHERE run_id=?",
                                                        Integer.class,
                                                        id))
                                        .isZero();
                        assertThat(objectMapper.writeValueAsString(store.events(owner, id, 0)))
                                        .doesNotContain("private");
                        verify(client, times(1)).complete(any());
                }

                @Test
                void cancelledRunCannotAcceptCallbackThatIgnoresInterrupt() throws Exception {
                        fixtureMode = "slow";
                        when(client.complete(any())).thenReturn(call("cancel", "fixture_reference", "{}"));
                        String id = start("B0", false);
                        var claim = store.claim(store.get(owner, id));
                        var runner = Thread.ofVirtual().start(() -> worker.execute(claim));
                        try {
                                assertThat(fixtureEntered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                                store.cancel(owner, id);
                                fixtureRelease.countDown();
                                runner.join(5000);
                                assertThat(runner.isAlive()).isFalse();
                                assertThat(store.get(owner, id).status()).isEqualTo("CANCELLED");
                                assertThat(
                                                jdbc.queryForObject(
                                                                "SELECT COUNT(*) FROM interview_tool_receipts WHERE run_id=?",
                                                                Integer.class,
                                                                id))
                                                .isZero();
                                verify(client, times(1)).complete(any());
                        } finally {
                                fixtureRelease.countDown();
                                runner.interrupt();
                                runner.join(5000);
                        }
                }

                @Test
                void cachedPayloadCannotBypassResultBudget() throws Exception {
                        var requests = new AtomicInteger();
                        var runId = new java.util.concurrent.atomic.AtomicReference<String>();
                        when(client.complete(any()))
                                        .thenAnswer(
                                                        inv -> {
                                                                int n = requests.incrementAndGet();
                                                                if (n == 2)
                                                                        jdbc.update(
                                                                                        "UPDATE interview_tool_receipts SET result_json=? WHERE run_id=?",
                                                                                        objectMapper
                                                                                                        .createObjectNode()
                                                                                                        .put("status", "EMPTY")
                                                                                                        .put("content", "x"
                                                                                                                        .repeat(300))
                                                                                                        .toString(),
                                                                                        runId.get());
                                                                return call("cache-" + n, "fixture_reference", "{}");
                                                        });
                        String id = start("A1", false);
                        runId.set(id);
                        run(id);
                        assertThat(store.get(owner, id).error()).isEqualTo("TOOL_RESULT_BUDGET");
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT COUNT(*) FROM interview_tool_receipts WHERE run_id=?",
                                                        Integer.class,
                                                        id))
                                        .isEqualTo(1);
                        verify(tools, times(1))
                                        .execute(
                                                        any(),
                                                        anyList(),
                                                        eq("fixture_reference"),
                                                        any(),
                                                        anyList(),
                                                        any(),
                                                        anyList(),
                                                        anyLong());
                        verify(client, times(2)).complete(any());
                }

                String start(String policy, boolean formal) throws Exception {
                        // 通过真实受理流程固定本用例的策略，结束后恢复默认策略。
                        // 若仅在受理后修改 loopPolicy，将与已固定的预算快照冲突并被拒绝。
                        org.springframework.test.util.ReflectionTestUtils.setField(model, "loopPolicy", policy);
                        try {
                                var response = performPost(
                                                "/api/interviews/start-commands",
                                                Map.of(
                                                                "requestId",
                                                                UUID.randomUUID().toString(),
                                                                "configuration",
                                                                Map.of(
                                                                                "targetRole",
                                                                                "Java 后端",
                                                                                "resumeSummary",
                                                                                "我负责接口设计，使用唯一约束保存请求标识。",
                                                                                "totalRounds",
                                                                                3,
                                                                                "experienceMode",
                                                                                formal ? "FORMAL" : "TEACHING")),
                                                token);
                                assertThat(response.getResponse().getStatus()).isEqualTo(202);
                                return (String) dataMap(response).get("runId");
                        } finally {
                                org.springframework.test.util.ReflectionTestUtils.setField(
                                                model, "loopPolicy", "B0");
                        }
                }

                ToolCallingChatClient.Response output(ToolCallingChatClient.Request request) {
                        return phaseResult(
                                        objectMapper,
                                        request,
                                        objectMapper.readTree(
                                                        "{\"question\":\"请说明你在相关经历中承担的个人职责？\",\"questionCompetencyId\":\"experience\"}"));
                }

                ToolCallingChatClient.Response call(String id, String name, String args) {
                        var message = objectMapper
                                        .createObjectNode()
                                        .put("role", "assistant")
                                        .putNull("content")
                                        .put("reasoning_content", "PRIVATE_STATE");
                        message.putArray("tool_calls")
                                        .addObject()
                                        .put("id", id)
                                        .put("type", "function")
                                        .putObject("function")
                                        .put("name", name)
                                        .put("arguments", args);
                        return new ToolCallingChatClient.Response(
                                        message,
                                        "tool_calls",
                                        new ModelUsage(ModelUsage.Status.REPORTED, 10L, 5L, 15L));
                }

                ToolCallingChatClient.Response read(String id) {
                        return call(
                                        id, "read_material_section", "{\"sectionId\":\"resume-0\",\"maxChars\":2000}");
                }

                @Test
                void s1ClosesToolsButReservesAValidatedFinalAttempt() throws Exception {
                        var n = new AtomicInteger();
                        when(client.complete(any()))
                                        .thenAnswer(
                                                        inv -> {
                                                                ToolCallingChatClient.Request request = inv
                                                                                .getArgument(0);
                                                                if (n.getAndIncrement() == 0) {
                                                                        assertThat(request.toolsAllowed()).isTrue();
                                                                        return read("first");
                                                                }
                                                                assertThat(request.toolsAllowed()).isFalse();
                                                                assertThat(request.messages().toString())
                                                                                .contains("STEP_LIMIT", "tool_call_id");
                                                                return output(request);
                                                        });
                        String id = start("S1", false);
                        run(id);
                        var trace = store.trace(owner, id, false);
                        assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
                        assertThat(store.get(owner, id).attempts()).isEqualTo(3);
                        assertThat(trace.actionSteps()).isEqualTo(1);
                        assertThat(trace.stopReason()).isEqualTo("STEP_LIMIT");
                        assertThat(trace.steps())
                                        .extracting(s -> s.decisionType())
                                        .containsExactly("RETRIEVE", "PROPOSE", "STOP");
                        assertThat(trace.actualExecutions()).isEqualTo(1);
                        assertThat(trace.resultCompleteness()).isEqualTo("VALIDATED");
                }

                @Test
                void repeatedEvidenceStopsAndCachesWithoutInventingProgress() throws Exception {
                        when(client.complete(any()))
                                        .thenReturn(read("one"), read("two"))
                                        .thenAnswer(inv -> output(inv.getArgument(0)));
                        String id = start("A1", false);
                        run(id);
                        var trace = store.trace(owner, id, false);
                        assertThat(trace.stopReason()).isEqualTo("NO_PROGRESS");
                        assertThat(trace.steps().get(1).newEvidence()).isZero();
                        assertThat(trace.actualExecutions()).isEqualTo(1);
                        assertThat(trace.cacheHits()).isEqualTo(1);
                        verify(tools, times(1))
                                        .execute(
                                                        any(),
                                                        anyList(),
                                                        eq("read_material_section"),
                                                        any(),
                                                        anyList(),
                                                        any(),
                                                        anyList(),
                                                        anyLong());
                        String publicJson = objectMapper.writeValueAsString(
                                        dataMap(performGet("/api/interview-runs/" + id + "/steps", token)));
                        assertThat(publicJson)
                                        .contains("NO_PROGRESS")
                                        .doesNotContain(
                                                        "PRIVATE_STATE", "唯一约束", "source_keys", "request_hash",
                                                        "arguments");
                        // 生成供浏览器只读回放的测试数据，不使用真实材料，也不调用模型供应商。
                        java.nio.file.Files.writeString(
                                        java.nio.file.Path.of("target/loop-run-trace.json"), publicJson);
                        java.nio.file.Files.writeString(
                                        java.nio.file.Path.of("target/loop-run-session.json"),
                                        objectMapper.writeValueAsString(
                                                        dataMap(
                                                                        performGet(
                                                                                        "/api/interviews/sessions/"
                                                                                                        + store.get(owner,
                                                                                                                        id)
                                                                                                                        .sessionId(),
                                                                                        token))));
                        java.nio.file.Files.writeString(
                                        java.nio.file.Path.of("target/loop-run-summary.json"),
                                        objectMapper.writeValueAsString(store.get(owner, id).view()));
                }

                @Test
                void twoMissesPermitOneChangedQueryThenStop() throws Exception {
                        when(client.complete(any()))
                                        .thenReturn(
                                                        call("q1", "search_knowledge",
                                                                        "{\"query\":\"redis\",\"topK\":2}"),
                                                        call("q2", "search_knowledge",
                                                                        "{\"query\":\"database\",\"topK\":2}"))
                                        .thenAnswer(inv -> output(inv.getArgument(0)));
                        String id = start("A1", false);
                        run(id);
                        var trace = store.trace(owner, id, false);
                        assertThat(trace.stopReason()).isEqualTo("NO_PROGRESS");
                        assertThat(trace.steps())
                                        .extracting(s -> s.decisionType())
                                        .containsExactly("RETRIEVE", "REVISE", "PROPOSE", "STOP");
                        assertThat(trace.steps().get(1).observation()).isEqualTo("EMPTY");
                }

                @Test
                void zeroToolPathDoesNotFabricateActionSteps() throws Exception {
                        when(client.complete(any())).thenAnswer(inv -> output(inv.getArgument(0)));
                        String id = start("S3", false);
                        run(id);
                        assertThat(store.trace(owner, id, false).actionSteps()).isZero();
                        assertThat(store.trace(owner, id, false).stopReason()).isEqualTo("COMPLETED");
                        assertThat(store.trace(owner, id, false).steps())
                                        .extracting(s -> s.decisionType())
                                        .containsExactly("PROPOSE", "STOP");
                }

                @Test
                void oversizedS1BatchClosesEveryAssociationWithoutExecutingAnyTool() throws Exception {
                        var request = read("one");
                        ((tools.jackson.databind.node.ArrayNode) request.message().path("tool_calls"))
                                        .add(read("two").message().path("tool_calls").get(0));
                        when(client.complete(any()))
                                        .thenAnswer(
                                                        new org.mockito.stubbing.Answer<ToolCallingChatClient.Response>() {
                                                                int n;

                                                                public ToolCallingChatClient.Response answer(
                                                                                org.mockito.invocation.InvocationOnMock inv) {
                                                                        if (n++ == 0)
                                                                                return request;
                                                                        ToolCallingChatClient.Request next = inv
                                                                                        .getArgument(0);
                                                                        assertThat(next.toolsAllowed()).isFalse();
                                                                        assertThat(
                                                                                        next.messages().stream()
                                                                                                        .filter(
                                                                                                                        m -> m.path("role")
                                                                                                                                        .asString()
                                                                                                                                        .equals(
                                                                                                                                                        "tool")))
                                                                                        .hasSize(2);
                                                                        assertThat(next.messages().toString())
                                                                                        .contains("SKIPPED", "one",
                                                                                                        "two");
                                                                        return output(next);
                                                                }
                                                        });
                        String id = start("S1", false);
                        run(id);
                        assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
                        assertThat(store.trace(owner, id, false).actionSteps()).isZero();
                        assertThat(store.trace(owner, id, false).actualExecutions()).isZero();
                        assertThat(store.trace(owner, id, false).stopReason()).isEqualTo("STEP_LIMIT");
                        verify(tools, never())
                                        .execute(
                                                        any(),
                                                        anyList(),
                                                        anyString(),
                                                        any(),
                                                        anyList(),
                                                        any(),
                                                        anyList(),
                                                        anyLong());
                }

                @Test
                void savedReceiptAndStepResumeAtomicallyWithNoReexecution() throws Exception {
                        String id = start("S3", false);
                        var claim = store.claim(store.get(owner, id));
                        var initial = objectMapper
                                        .createObjectNode()
                                        .put("stage", "MODEL")
                                        .put("outputPhase", "ACTION")
                                        .put("repaired", false)
                                        .put("actionRepaired", false);
                        initial.putArray("sources");
                        initial.putArray("messages")
                                        .addObject()
                                        .put("role", "system")
                                        .put("content", InterviewDecisionContract.ACTION_PROMPT);
                        store.checkpoint(claim, initial.toString(), "MODEL", null, null);
                        var cp = objectMapper
                                        .createObjectNode()
                                        .put("outputPhase", "ACTION")
                                        .put("actionRepaired", false)
                                        .put("stage", "TOOL")
                                        .put("repaired", false)
                                        .put("toolIndex", 1)
                                        .put("batchReserved", true);
                        cp.putArray("sources");
                        cp.putArray("messages")
                                        .add(
                                                        objectMapper
                                                                        .createObjectNode()
                                                                        .put("role", "system")
                                                                        .put("content", InterviewDecisionContract.ACTION_PROMPT))
                                        .add(read("saved").message())
                                        .add(
                                                        objectMapper
                                                                        .createObjectNode()
                                                                        .put("role", "tool")
                                                                        .put("tool_call_id", "saved")
                                                                        .put("content", "{\"status\":\"FOUND\"}"));
                        cp.set("response", read("saved").message());
                        String attempt = store.reserveModelAttempt(claim, prepared("fixture")).id();
                        store.response(claim, attempt, "{}", "{}", 1, cp.toString(), "TOOL");
                        store.batch(claim, 1, cp.toString());
                        var args = objectMapper.readTree("{\"sectionId\":\"resume-0\",\"maxChars\":2000}");
                        var result = objectMapper.readTree(
                                        "{\"status\":\"FOUND\",\"sha256\":\"frozen-version\",\"content\":\"合成资料\"}");
                        var observation = AgentLoopState.observe(objectMapper, cp, "read_material_section", args,
                                        result);
                        store.receipt(
                                        claim,
                                        "key",
                                        "saved",
                                        "read_material_section",
                                        args.toString(),
                                        "source",
                                        result.toString(),
                                        cp.toString(),
                                        false,
                                        observation);
                        jdbc.update(
                                        "UPDATE interview_runs SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
                                        id);
                        var next = store.claim(store.get(owner, id));
                        assertThatThrownBy(
                                        () -> store.receipt(
                                                        claim,
                                                        "stale",
                                                        "stale",
                                                        "read_material_section",
                                                        "{}",
                                                        "s",
                                                        "{}",
                                                        cp.toString(),
                                                        false,
                                                        observation))
                                        .hasMessage("RUN_FENCED");
                        when(client.complete(any())).thenAnswer(inv -> output(inv.getArgument(0)));
                        worker.execute(next);
                        assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
                        assertThat(store.trace(owner, id, false).actionSteps()).isEqualTo(1);
                        assertThat(store.trace(owner, id, false).actualExecutions()).isEqualTo(1);
                        verify(tools, never())
                                        .execute(
                                                        any(),
                                                        anyList(),
                                                        anyString(),
                                                        any(),
                                                        anyList(),
                                                        any(),
                                                        anyList(),
                                                        anyLong());
                        verify(client, times(2)).complete(any());
                }

                @Test
                void cancelledAndExpiredRunsExposeNoValidatedBusinessResult() throws Exception {
                        String cancelled = start("S3", false);
                        store.cancel(owner, cancelled);
                        assertThat(store.trace(owner, cancelled, false).stopReason()).isEqualTo("CANCELLED");
                        assertThat(store.trace(owner, cancelled, false).resultCompleteness()).isEqualTo("NONE");
                        String expired = start("S3", false);
                        var claim = store.claim(store.get(owner, expired));
                        jdbc.update(
                                        "UPDATE interview_runs SET deadline=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
                                        expired);
                        worker.execute(claim);
                        assertThat(store.get(owner, expired).status()).isEqualTo("EXPIRED");
                        assertThat(store.trace(owner, expired, false).resultCompleteness()).isEqualTo("NONE");
                        verify(client, never()).complete(any());
                }

                @Test
                void damagedSavedToolProgressFailsWithoutNewCallsOrBudgetReservation() throws Exception {
                        String id = start("S3", false);
                        var claim = store.claim(store.get(owner, id));
                        var cp = objectMapper
                                        .createObjectNode()
                                        .put("outputPhase", "ACTION")
                                        .put("actionRepaired", false)
                                        .put("stage", "TOOL")
                                        .put("repaired", false)
                                        .put("toolIndex", 1)
                                        .put("batchReserved", true);
                        cp.putArray("sources");
                        cp.putArray("messages").add(read("missing-receipt").message());
                        cp.set("response", read("missing-receipt").message());
                        store.checkpoint(claim, cp.toString(), "TOOL", null, null);
                        jdbc.update(
                                        "UPDATE interview_runs SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
                                        id);

                        run(id);

                        var failed = store.get(owner, id);
                        assertThat(failed.status()).isEqualTo("FAILED");
                        assertThat(failed.error()).isEqualTo("RUN_CHECKPOINT_INVALID");
                        assertThat(failed.attempts()).isZero();
                        assertThat(failed.batches()).isZero();
                        assertThat(failed.tools()).isZero();
                        verify(client, never()).complete(any());
                        verify(tools, never())
                                        .execute(
                                                        any(),
                                                        anyList(),
                                                        anyString(),
                                                        any(),
                                                        anyList(),
                                                        any(),
                                                        anyList(),
                                                        anyLong());
                }

                @Test
                void changedConfigurationCannotExecuteToolsFromAnOlderCheckpoint() throws Exception {
                        String id = start("S3", false);
                        var claim = store.claim(store.get(owner, id));
                        var cp = objectMapper
                                        .createObjectNode()
                                        .put("outputPhase", "ACTION")
                                        .put("actionRepaired", false)
                                        .put("stage", "TOOL")
                                        .put("repaired", false)
                                        .put("toolIndex", 0)
                                        .put("batchReserved", false);
                        cp.putArray("sources");
                        cp.putArray("messages").add(read("older-toolset").message());
                        cp.set("response", read("older-toolset").message());
                        store.checkpoint(claim, cp.toString(), "TOOL", null, null);
                        var config = (tools.jackson.databind.node.ObjectNode) objectMapper
                                        .readTree(store.get(owner, id).config());
                        config.put("toolset", "older-toolset");
                        jdbc.update(
                                        "UPDATE interview_runs SET config_json=? WHERE id=?", config.toString(), id);

                        worker.execute(claim);

                        var failed = store.get(owner, id);
                        assertThat(failed.status()).isEqualTo("FAILED");
                        assertThat(failed.error()).isEqualTo("MODEL_CONFIG_CHANGED");
                        assertThat(failed.attempts()).isZero();
                        assertThat(failed.batches()).isZero();
                        assertThat(failed.tools()).isZero();
                        verify(client, never()).complete(any());
                        verify(tools, never())
                                        .execute(
                                                        any(),
                                                        anyList(),
                                                        anyString(),
                                                        any(),
                                                        anyList(),
                                                        any(),
                                                        anyList(),
                                                        anyLong());
                }

                @Test
                void formalTraceIsHiddenAndOtherOwnersCannotReadIt() throws Exception {
                        when(client.complete(any()))
                                        .thenReturn(read("formal"))
                                        .thenAnswer(inv -> output(inv.getArgument(0)));
                        String id = start("S1", true);
                        run(id);
                        var trace = dataMap(performGet("/api/interview-runs/" + id + "/steps", token));
                        assertThat(trace.get("hidden")).isEqualTo(true);
                        assertThat((java.util.List<?>) trace.get("steps")).isEmpty();
                        String other = account("其他账号");
                        assertThat(
                                        performGet("/api/interview-runs/" + id + "/steps", other)
                                                        .getResponse()
                                                        .getStatus())
                                        .isEqualTo(404);
                        assertThat(
                                        performGet(
                                                        "/api/interviews/sessions/"
                                                                        + store.get(owner, id).sessionId()
                                                                        + "/runs",
                                                        other)
                                                        .getResponse()
                                                        .getStatus())
                                        .isEqualTo(404);
                }

                @Test
                void pruningPrivateRecordsKeepsSafeStepsAndAccounting() throws Exception {
                        when(client.complete(any()))
                                        .thenReturn(read("read"))
                                        .thenAnswer(inv -> output(inv.getArgument(0)));
                        String id = start("S1", false);
                        run(id);
                        jdbc.update(
                                        "UPDATE interview_runs SET updated_at=TIMESTAMPADD(DAY,-8,CURRENT_TIMESTAMP(6)) WHERE id=?",
                                        id);
                        store.prune();
                        assertThat(store.trace(owner, id, false).actualExecutions()).isEqualTo(1);
                        assertThat(store.trace(owner, id, false).actionSteps()).isEqualTo(1);
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT COUNT(*) FROM interview_tool_receipts WHERE run_id=?",
                                                        Integer.class,
                                                        id))
                                        .isZero();
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT COUNT(*) FROM interview_run_steps WHERE run_id=? AND source_keys_json IS NOT NULL",
                                                        Integer.class,
                                                        id))
                                        .isZero();
                }

                @Test
                void duplicateCallIdsRejectWholeBatch() throws Exception {
                        var request = read("same");
                        ((tools.jackson.databind.node.ArrayNode) request.message().path("tool_calls"))
                                        .add(read("same").message().path("tool_calls").get(0));
                        when(client.complete(any())).thenReturn(request);
                        String id = start("S3", false);
                        run(id);
                        assertThat(store.get(owner, id).error()).isEqualTo("TOOL_CALL_ID");
                        assertThat(store.trace(owner, id, false).actualExecutions()).isZero();
                        verify(tools, never())
                                        .execute(
                                                        any(),
                                                        anyList(),
                                                        anyString(),
                                                        any(),
                                                        anyList(),
                                                        any(),
                                                        anyList(),
                                                        anyLong());
                }

                @org.junit.jupiter.params.ParameterizedTest
                @org.junit.jupiter.params.provider.ValueSource(strings = { "B0", "S1", "S3" })
                void commonBudgetProtocolComparison(String policy) throws Exception {
                        var calls = new AtomicInteger();
                        when(client.complete(any()))
                                        .thenAnswer(
                                                        inv -> {
                                                                ToolCallingChatClient.Request request = inv
                                                                                .getArgument(0);
                                                                int n = calls.getAndIncrement();
                                                                if (n == 0)
                                                                        return call(
                                                                                        "missing",
                                                                                        "read_material_section",
                                                                                        "{\"sectionId\":\"missing\",\"maxChars\":2000}");
                                                                assertThat(request.messages().toString())
                                                                                .contains("MISSING");
                                                                if (request.toolsAllowed() && n == 1)
                                                                        return read("available");
                                                                assertThat(request.toolsAllowed()).isFalse();
                                                                return output(request);
                                                        });
                        String id = start(policy, false);
                        long started = System.nanoTime();
                        run(id);
                        long elapsed = (System.nanoTime() - started) / 1000000;
                        var row = store.get(owner, id);
                        assertThat(row.status()).isEqualTo("SUCCEEDED");
                        assertThat(row.attempts()).isEqualTo(3);
                        assertThat(row.tools()).isEqualTo(1);
                        var report = objectMapper
                                        .createObjectNode()
                                        .put("scope", "SYNTHETIC_PROTOCOL_ONLY_NOT_SEMANTIC_BENEFIT")
                                        .put("policy", policy)
                                        .put("status", row.status())
                                        .put("modelAttempts", row.attempts())
                                        .put("reservedToolRequests", row.tools())
                                        .put("elapsedMs", elapsed)
                                        .put("reportedInputTokens", row.attempts() * 10)
                                        .put("reportedOutputTokens", row.attempts() * 5)
                                        .put("priceVersion", "NOT_APPLICABLE_FIXTURE")
                                        .put("realProviderCalls", 0)
                                        .put("heldoutUsed", false);
                        report.set("trace", objectMapper.valueToTree(store.trace(owner, id, false)));
                        java.nio.file.Files.writeString(
                                        java.nio.file.Path.of("target/loop-comparison-" + policy + ".json"),
                                        report.toString());
                }

                @Test
                void unknownReservationIsNotAnExecutedToolAndQueriesNeverRunTheProvider() throws Exception {
                        String id = start("S3", false);
                        var claim = store.claim(store.get(owner, id));
                        store.batch(claim, 1, "{\"stage\":\"TOOL\"}");
                        store.fail(claim, "RUN_EXECUTION_FAILED");
                        var trace = store.trace(owner, id, false);
                        assertThat(trace.actualExecutions()).isZero();
                        assertThat(trace.unknownExecutions()).isEqualTo(1);
                        performGet("/api/interview-runs/" + id + "/steps", token);
                        performGet("/api/interview-runs/" + id, token);
                        verify(client, never()).complete(any());
                }

                @Test
                void receiptFailureRollsBackItsStepCheckpointAndAccounting() throws Exception {
                        String id = start("S3", false);
                        var claim = store.claim(store.get(owner, id));
                        var cp = objectMapper
                                        .createObjectNode()
                                        .put("outputPhase", "ACTION")
                                        .put("actionRepaired", false)
                                        .put("stage", "TOOL");
                        store.batch(claim, 1, cp.toString());
                        String before = store.get(owner, id).checkpoint();
                        var args = objectMapper.readTree("{\"sectionId\":\"resume-0\",\"maxChars\":2000}");
                        var result = objectMapper.readTree(
                                        "{\"status\":\"FOUND\",\"content\":\"合成资料\",\"sha256\":\"version\"}");
                        var observation = AgentLoopState.observe(objectMapper, cp, "read_material_section", args,
                                        result);
                        String trigger = "loop_" + UUID.randomUUID().toString().replace("-", "");
                        jdbc.execute(
                                        "CREATE TRIGGER "
                                                        + trigger
                                                        + " BEFORE INSERT ON interview_run_steps FOR EACH ROW BEGIN IF NEW.run_id='"
                                                        + id
                                                        + "' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic step fault'; END IF; END");
                        try {
                                assertThatThrownBy(
                                                () -> store.receipt(
                                                                claim,
                                                                "key",
                                                                "call",
                                                                "read_material_section",
                                                                args.toString(),
                                                                "s",
                                                                result.toString(),
                                                                cp.toString(),
                                                                false,
                                                                observation))
                                                .isInstanceOf(RuntimeException.class);
                                assertThat(store.get(owner, id).checkpoint()).isEqualTo(before);
                                assertThat(store.trace(owner, id, false).actualExecutions()).isZero();
                                assertThat(store.trace(owner, id, false).steps()).isEmpty();
                                assertThat(
                                                jdbc.queryForObject(
                                                                "SELECT COUNT(*) FROM interview_tool_receipts WHERE run_id=?",
                                                                Integer.class,
                                                                id))
                                                .isZero();
                        } finally {
                                jdbc.execute("DROP TRIGGER " + trigger);
                                store.cancel(owner, id);
                        }
                }
        }
}
