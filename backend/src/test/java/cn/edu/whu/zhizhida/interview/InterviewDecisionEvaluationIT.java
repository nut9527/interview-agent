package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import cn.edu.whu.zhizhida.ai.api.*;
import cn.edu.whu.zhizhida.evaluation.api.*;
import cn.edu.whu.zhizhida.evaluation.api.AssessmentResult.*;
import cn.edu.whu.zhizhida.interview.api.*;
import cn.edu.whu.zhizhida.interview.api.InterviewAgentState.*;
import cn.edu.whu.zhizhida.interview.api.InterviewAiService.InterviewAiReply;
import cn.edu.whu.zhizhida.interview.api.InterviewSession.InterviewTurn;
import cn.edu.whu.zhizhida.interview.domain.InterviewAgentPolicy;
import cn.edu.whu.zhizhida.interview.domain.InterviewTransitions;
import cn.edu.whu.zhizhida.interview.execution.*;
import cn.edu.whu.zhizhida.knowledge.KnowledgeRepository;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument;
import cn.edu.whu.zhizhida.knowledge.domain.KnowledgeText;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InterviewDecisionEvaluationIT {

    @SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
            "interview.runs.worker-enabled=false", "interview.agent.adaptive-routing-enabled=true",
            "interview.runs.decision-protocol=phased-v1", "ai.provider=deepseek"})
    @Nested
    class AdaptiveRouting extends InterviewRunScenario {
        @MockitoBean ToolCallingChatClient client;
        @Autowired InterviewSessionRepository sessions;
        @Autowired JdbcTemplate jdbc;
        String answer = "我负责接口设计，协作交付的边界还需要核实。";

        @BeforeEach void setup() throws Exception {
            when(client.configuration()).thenReturn(new ToolCallingChatClient.Config("adaptive-fixture", "enabled", "fixture-v1"));
        }
        ToolCallingChatClient.Response output(Object payload) {
            return new ToolCallingChatClient.Response(objectMapper.createObjectNode().put("role", "assistant")
                    .put("content", objectMapper.writeValueAsString(payload)), "stop", new ModelUsage(ModelUsage.Status.REPORTED, 50L, 30L, 80L));
        }
        ToolCallingChatClient.Response response(ToolCallingChatClient.Request request, Object finalResult) {
            boolean action = request.messages().getFirst().path("content").asText().startsWith(InterviewDecisionContract.ACTION_PROMPT);
            return output(action ? Map.of("version", "phased-v1", "phase", "ACTION", "action", "COMPLETE", "reason", "已有资料足够")
                    : Map.of("version", "phased-v1", "phase", "FINAL", "result", finalResult));
        }
        String opened(String format) throws Exception {
            doAnswer(inv -> response(inv.getArgument(0),
                    Map.of("question", "请介绍你负责的一次接口设计经历？", "questionCompetencyId", "experience"))).when(client).complete(any());
            var r = performPost("/api/interviews/start-commands", Map.of("requestId", UUID.randomUUID().toString(),
                    "configuration", Map.of("targetRole", "Java 后端", "totalRounds", 6, "experienceMode", format)), token);
            assertThat(r.getResponse().getStatus()).isEqualTo(202);
            String id = (String)dataMap(r).get("runId"); runSuccessfully(id);
            return store.get(owner,id).sessionId();
        }
        InterviewAiReply review(String target, String question) {
            return new InterviewAiReply("职责已说明，仍需补充验证。", question, "",
                    new Decision(Level.PARTIAL,"我负责接口设计","边界尚待核对",Action.ADVANCE,target,
                            "本轮提到了协作边界，因此调整下一项考察", "补充实际依据",Probe.OPENING,"核对个人职责边界",target));
        }

        @Test void modelSelectedNonAdjacentTargetCommitsWithAnswerAndReplaysWithoutAnotherAttemptOrQuotaCharge() throws Exception {
            String session = opened("TEACHING");
            var initial = sessions.findById(owner,session).orElseThrow();
            assertThat(initial.agent().version()).isEqualTo(InterviewAgentPolicy.ADAPTIVE_VERSION);
            String chosen = initial.agent().plan().getLast().id();
            doAnswer(inv -> {
                ToolCallingChatClient.Request request = inv.getArgument(0);
                assertThat(request.messages().toString()).contains("eligibleNext", "adaptive-v1");
                return response(request, review(chosen,"请说明你在目标交付中怎样核对个人职责和交接边界？"));
            }).when(client).complete(any());
            String id = acceptAnswer(session,"adaptive-answer-1",1, answer); runSuccessfully(id);
            var first = sessions.findById(owner,session).orElseThrow();
            assertThat(first.agent().activeCompetencyId()).isEqualTo(chosen);
            assertThat(first.agent().plan()).isEqualTo(initial.agent().plan());
            assertThat(first.agent().evaluations().getFirst().competencyId()).isEqualTo("experience");
            assertThat(store.get(owner,id).attempts()).isEqualTo(2);
            clearInvocations(client);
            assertThat(acceptAnswer(session,"adaptive-answer-1",1, answer)).isEqualTo(id);
            verifyNoInteractions(client);
            assertThat(jdbc.queryForObject("SELECT used_units FROM request_quotas WHERE scope_key=?", Integer.class,
                    InterviewRunStore.sha("ai-day:"+owner))).isEqualTo(5);
            assertThat(objectMapper.writeValueAsString(store.events(owner,id,0))).doesNotContain(answer, "本轮提到了协作");
            assertThat(dataMap(performGet("/api/interviews/sessions/"+session,token)).get("agent")).isNotNull();
        }

        @Test void invalidTargetRepairsWithinTheSameRunAndRepeatedInvalidTargetDoesNotAdvance() throws Exception {
            String session = opened("TEACHING");
            var initial = sessions.findById(owner,session).orElseThrow();
            String target = initial.agent().plan().getLast().id();
            var calls = new AtomicInteger();
            doAnswer(inv -> {
                ToolCallingChatClient.Request req = inv.getArgument(0);
                boolean action = req.messages().getFirst().path("content").asText().startsWith(InterviewDecisionContract.ACTION_PROMPT);
                return response(req, review(action || calls.incrementAndGet() == 1 ? "outside-scope" : target,
                        "请说明你实际验证目标交付职责边界时采用了什么证据？"));
            }).when(client).complete(any());
            String id = acceptAnswer(session,"repair-target-1",1, answer); runSuccessfully(id);
            assertThat(store.get(owner,id).attempts()).isEqualTo(3);
            assertThat(sessions.findById(owner,session).orElseThrow().agent().activeCompetencyId()).isEqualTo(target);
            doAnswer(inv -> response(inv.getArgument(0), review("experience", "能否回到最初的岗位经历？"))).when(client).complete(any());
            String invalid = acceptAnswer(session,"invalid-target-2",2, answer);
            run(invalid);
            assertThat(store.get(owner,invalid).status()).isEqualTo("FAILED");
            assertThat(store.get(owner,invalid).attempts()).isEqualTo(3);
            assertThat(sessions.findById(owner,session).orElseThrow().turnCount()).isEqualTo(2);
            assertThat(sessions.findById(owner,session).orElseThrow().agent().evaluations()).hasSize(1);
        }

        @Test void savedFinalResponseResumesWithoutCallingModelAndCancelLeavesNoDecision() throws Exception {
            String session = opened("TEACHING");
            var initial = sessions.findById(owner,session).orElseThrow();
            String chosen = initial.agent().plan().getLast().id();
            String id = acceptAnswer(session,"saved-target-1",1, answer);
            var claim = store.claim(store.get(owner,id));
            var cp = objectMapper.createObjectNode().put("stage","MODEL").put("outputPhase","FINAL")
                    .put("repaired",false).put("actionRepaired",false);
            cp.putArray("sources"); cp.putArray("messages").addObject().put("role","system").put("content","fixture");
            store.checkpoint(claim,cp.toString(),"MODEL",null,null);
            String attempt = store.reserveModelAttempt(claim, prepared("saved-final")).id();
            cp.set("response", output(Map.of("version","phased-v1","phase","FINAL","result",
                    review(chosen,"请说明目标交付中你核对职责边界的实际依据？"))).message());
            cp.withArray("messages").add(cp.path("response")); cp.put("stage","VALIDATE");
            store.response(claim,attempt,"{}","{}",1,cp.toString(),"VALIDATE");
            clearInvocations(client);
            worker.execute(claim);
            verify(client,never()).complete(any());
            assertThat(store.get(owner,id).status()).isEqualTo("SUCCEEDED");
            var first = sessions.findById(owner,session).orElseThrow();
            assertThat(first.agent().activeCompetencyId()).isEqualTo(chosen);
            String cancelled = acceptAnswer(session,"cancel-target-2",2, answer);
            var cancelClaim = store.claim(store.get(owner,cancelled)); store.cancel(owner,cancelled); worker.execute(cancelClaim);
            assertThat(sessions.findById(owner,session).orElseThrow().agent()).isEqualTo(first.agent());
        }

        @Test void formalSessionsRetainFixedOrderAndRejectTheSameReorder() throws Exception {
            String session = opened("FORMAL");
            var initial = sessions.findById(owner,session).orElseThrow();
            assertThat(initial.agent().version()).isEqualTo("interview-agent-v3");
            doAnswer(inv -> response(inv.getArgument(0),
                    review(initial.agent().plan().getLast().id(),"请直接跳到最后一项能力？"))).when(client).complete(any());
            String id = acceptAnswer(session,"formal-target-1",1, answer); run(id);
            assertThat(store.get(owner,id).status()).isEqualTo("FAILED");
            assertThat(sessions.findById(owner,session).orElseThrow().agent().evaluations()).isEmpty();
            assertThat(dataMap(performGet("/api/interviews/sessions/"+session,token)).get("agent")).isNull();
        }

        @Test void reversedTrajectoryPreservesHistoricalChoiceAndCommandReplayReturnsCurrentSession() throws Exception {
            String session = opened("TEACHING");
            var initial = sessions.findById(owner,session).orElseThrow();
            var policy = new InterviewAgentPolicy();
            InterviewSession first = null;
            for (int round = 1; round <= 6; round++) {
                var current = sessions.findById(owner,session).orElseThrow();
                var route = policy.routing(current.agent(), round, 6);
                var action = route.allowedActions().contains(Action.ADVANCE) ? Action.ADVANCE
                        : route.allowedActions().contains(Action.FOLLOW_UP) ? Action.FOLLOW_UP : Action.FINISH;
                String target = action == Action.ADVANCE ? route.eligibleNext().getLast().id() : route.current().id();
                var d = new Decision(Level.PARTIAL,"我负责接口设计","待补充边界",action,target,
                        "根据当前观察选择下一项验证", "补充证据", action == Action.ADVANCE ? Probe.OPENING
                        : action == Action.FOLLOW_UP ? route.allowedProbes().getFirst() : Probe.NONE,
                        "核对事实边界",action == Action.FINISH ? "" : target);
                var payload = new InterviewAiReply("已说明行动，验证仍待补充。",action == Action.FINISH ? ""
                        : "针对能力"+target+"，请说明第"+round+"项实际行动有哪些可核对的边界？",
                        action == Action.FINISH ? "已覆盖计划范围，仍需逐项补充验证依据。" : "",d);
                doAnswer(inv -> response(inv.getArgument(0),payload)).when(client).complete(any());
                runSuccessfully(acceptAnswer(session,"trajectory-answer-"+round,round, answer));
                if (round == 1) first = sessions.findById(owner,session).orElseThrow();
            }
            var completed = sessions.findById(owner,session).orElseThrow();
            assertThat(completed.state()).isEqualTo(InterviewState.FINISHED);
            assertThat(completed.agent().evaluations().stream().map(Evaluation::competencyId).distinct())
                    .containsExactlyInAnyOrderElementsOf(initial.agent().plan().stream().map(Competency::id).toList());
            assertThat(completed.agent().through(1)).isEqualTo(first.agent());
            clearInvocations(client);
            var replay = dataMap(answerInterview(session,
                    Map.of("requestId","trajectory-answer-1","expectedTurn",1,"answer",answer),token));
            assertThat(objectMapper.<tools.jackson.databind.JsonNode>valueToTree(replay))
                    .isEqualTo(objectMapper.<tools.jackson.databind.JsonNode>valueToTree(completed.publicView()));
            verify(client,never()).complete(any());
            assertThat(sessions.findById(owner,session).orElseThrow()).isEqualTo(completed);
        }
    }

    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "interview.runs.worker-enabled=false", "ai.provider=deepseek" })
    @Nested
    class Evidence extends InterviewRunScenario {
        @MockitoBean ToolCallingChatClient client;
        @Autowired InterviewSessionRepository sessions;
        @Autowired InterviewStateMachine machine;
        @org.springframework.test.context.bean.override.mockito.MockitoSpyBean InterviewAiService ai;
        static final String ANSWER = "我负责订单接口和异常回滚。\r\n因为重试可能重复写入，我选择请求号唯一约束，并验证冲突返回原结果😀。";

        @BeforeEach void setup() throws Exception {
            when(client.configuration()).thenReturn(new ToolCallingChatClient.Config("controlled-assessment", "enabled", "fixture-v1"));
        }
        tools.jackson.databind.JsonNode data(org.springframework.test.web.servlet.MvcResult result) throws Exception {
            return objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
        }
        ToolCallingChatClient.Response response(Object payload) {
            return new ToolCallingChatClient.Response(objectMapper.createObjectNode().put("role", "assistant")
                    .put("content", objectMapper.writeValueAsString(payload)), "stop", ModelUsage.unknown(ModelUsage.Status.MISSING));
        }
        String opened(String format) throws Exception {
            when(client.complete(any())).thenReturn(response(Map.of("question", "请说明你在相关经历中承担的个人职责？", "questionCompetencyId", "experience")));
            var accepted = performPost("/api/interviews/start-commands", Map.of("requestId", UUID.randomUUID().toString(),
                    "configuration", Map.of("targetRole", "Java 后端", "totalRounds", 3, "experienceMode", format)), token);
            assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
            var id = data(accepted).path("runId").asText(); run(id);
            assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
            return store.get(owner, id).sessionId();
        }
        AssessmentDraft draft(boolean valid) {
            int second = ANSWER.indexOf("因为");
            return new AssessmentDraft(List.of(
                    new AssessmentDraft.Item(AssessmentResult.Dimension.RESPONSIBILITY, Observation.OBSERVED, "回答明确本人负责接口和回滚",
                            ClaimStatus.SUPPORTED, "自述尚无独立实现记录", List.of(new AssessmentDraft.Quote(valid ? 0 : 1, 13, ANSWER.substring(0, 13)))),
                    new AssessmentDraft.Item(AssessmentResult.Dimension.CAUSAL_EXPLANATION, Observation.OBSERVED, "回答解释重试风险与约束选择的关系",
                            ClaimStatus.SUPPORTED, "方案正确性与实际验证仍需复核", List.of(new AssessmentDraft.Quote(second, ANSWER.length(), ANSWER.substring(second))))));
        }
        String answer(String sid, String key, int round, boolean valid) throws Exception {
            return answer(sid, key, round, draft(valid));
        }
        String answer(String sid, String key, int round, AssessmentDraft assessment) throws Exception {
            var s = sessions.findById(owner, sid).orElseThrow();
            var pending = (InterviewTransitions.PendingAnswer) new InterviewTransitions().answer(s, ANSWER, key, round);
            var mock = new MockInterviewAiService(objectMapper, BusinessMetrics.disabled());
            var reply = mock.reviewAnswer(s.targetRole(), machine.runContext(s, pending, List.of()), pending.turns(), round, pending.finalRound());
            when(client.complete(any())).thenReturn(response(new InterviewAiReply(reply.feedback(), reply.nextQuestion(), reply.finalSummary(), reply.agentDecision(), assessment)));
            var accepted = performPost("/api/interviews/sessions/" + sid + "/answer-commands",
                    Map.of("requestId", key, "expectedTurn", round, "answer", ANSWER), token);
            assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
            String id = data(accepted).path("runId").asText(); run(id); return id;
        }
        @Test void durableEvidenceSurvivesReplayAndFormalProjectionWithOwnerIsolation() throws Exception {
            var sid = opened("FORMAL");
            String first = null;
            for (int round = 1; round <= 3; round++) {
                var id = answer(sid, "evidence_round_" + round, round, true);
                assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
                if (round == 1) first = id;
                var view = data(performGet("/api/interviews/sessions/" + sid, token));
                if (round < 3) assertThat(view.path("agent").isNull()).isTrue();
            }
            var saved = data(performGet("/api/interviews/sessions/" + sid, token));
            var assessment = saved.path("agent").path("evaluations").get(0).path("assessment");
            assertThat(assessment.path("items").size()).isEqualTo(2);
            assertThat(assessment.path("evidence").size()).isEqualTo(2);
            for (var e : assessment.path("evidence")) {
                assertThat(e.path("ownerId").asLong()).isEqualTo(owner);
                assertThat(e.path("sourceId").asText()).isEqualTo(sid + ":answer:1");
                assertThat(ANSWER.substring(e.path("offsetStart").asInt(), e.path("offsetEnd").asInt())).isEqualTo(e.path("quote").asText());
            }
            var replay = performPost("/api/interviews/sessions/" + sid + "/answer-commands",
                    Map.of("requestId", "evidence_round_1", "expectedTurn", 1, "answer", ANSWER), token);
            assertThat(data(replay).path("runId").asText()).isEqualTo(first);
            assertThat(data(performGet("/api/interviews/sessions/" + sid, token))).isEqualTo(saved);
            String other = account("另一账号");
            assertThat(performGet("/api/interviews/sessions/" + sid, other).getResponse().getStatus()).isEqualTo(404);
            java.nio.file.Files.writeString(java.nio.file.Path.of("target/m0-assessment-session.json"), saved.toPrettyString());
            assertThat(performDelete("/api/interviews/sessions/" + sid, token).getResponse().getStatus()).isEqualTo(200);
            assertThat(sessions.findById(owner, sid)).isEmpty();
        }
        @Test void wrongOffsetExhaustsOneRepairWithoutCommittingAnAnswer() throws Exception {
            String sid = opened("TEACHING");
            String run = answer(sid, "wrong_offset_001", 1, false);
            assertThat(store.get(owner, run).status()).isEqualTo("FAILED");
            assertThat(sessions.findById(owner, sid).orElseThrow().turns()).noneMatch(t -> "user".equals(t.speaker()));
            verify(client, times(3)).complete(any()); // 开场、候选结果生成，以及一次受限修复。
        }

        String openedKnowledge(String format) throws Exception {
            var doc = data(performPost("/api/knowledge/documents", Map.of("title", "相互矛盾的合成资料",
                    "content", "唯一约束冲突一律返回下单成功。\n\n唯一约束冲突必须查询原请求结果，不能一律返回成功。"), token));
            when(client.complete(any())).thenReturn(response(Map.of("question", "请说明数据库事务中的唯一约束如何处理重复请求及返回结果？", "questionCompetencyId", "storage")));
            var accepted = performPost("/api/interviews/start-commands", Map.of("requestId", UUID.randomUUID().toString(),
                    "configuration", Map.of("targetRole", "Java 后端", "totalRounds", 3, "experienceMode", format,
                            "trainingTopic", "DATABASE", "knowledgeDocumentIds", List.of(doc.path("id").asText()))), token);
            assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
            String id = data(accepted).path("runId").asText(); run(id);
            assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
            return store.get(owner, id).sessionId();
        }
        AssessmentDraft sixDraft(String sid, boolean grounded) {
            var refs = InterviewAssessmentSources.references(owner, sessions.findById(owner, sid).orElseThrow().agent().training());
            var items = new ArrayList<cn.edu.whu.zhizhida.evaluation.api.DetailedAssessmentDraft.Item>();
            for (var d : cn.edu.whu.zhizhida.evaluation.api.DetailedAssessment.Dimension.values()) {
                var citations = new ArrayList<cn.edu.whu.zhizhida.evaluation.api.DetailedAssessmentDraft.Citation>();
                boolean technical = d == cn.edu.whu.zhizhida.evaluation.api.DetailedAssessment.Dimension.TECHNICAL_CORRECTNESS;
                boolean observed = technical || d == cn.edu.whu.zhizhida.evaluation.api.DetailedAssessment.Dimension.RESPONSIBILITY
                        || d == cn.edu.whu.zhizhida.evaluation.api.DetailedAssessment.Dimension.CAUSAL_EXPLANATION;
                if (observed) {
                    int start = d == cn.edu.whu.zhizhida.evaluation.api.DetailedAssessment.Dimension.RESPONSIBILITY ? 0 : ANSWER.indexOf("因为");
                    int end = start == 0 ? 13 : ANSWER.length();
                    citations.add(new cn.edu.whu.zhizhida.evaluation.api.DetailedAssessmentDraft.Citation("answer", start, end, ANSWER.substring(start,end),
                            cn.edu.whu.zhizhida.evaluation.api.DetailedAssessmentDraft.Relation.SELF_REPORT));
                }
                if (technical && grounded) for (int i = 0; i < refs.size(); i++) {
                    var r = refs.get(i);
                    citations.add(new cn.edu.whu.zhizhida.evaluation.api.DetailedAssessmentDraft.Citation(r.key(), 0, r.text().length(), r.text(),
                            i == 0 ? cn.edu.whu.zhizhida.evaluation.api.DetailedAssessmentDraft.Relation.SUPPORT
                                    : cn.edu.whu.zhizhida.evaluation.api.DetailedAssessmentDraft.Relation.COUNTEREVIDENCE));
                }
                items.add(new cn.edu.whu.zhizhida.evaluation.api.DetailedAssessmentDraft.Item(d,
                        observed ? Observation.OBSERVED : Observation.NOT_OBSERVED, observed ? "回答陈述职责、重试风险与约束选择" : "本轮未覆盖此维度",
                        observed ? ClaimStatus.SUPPORTED : ClaimStatus.INSUFFICIENT, "资料需复核，经历尚未验证", technical, citations));
            }
            return new AssessmentDraft(null, new cn.edu.whu.zhizhida.evaluation.api.DetailedAssessmentDraft("six-dimensions-v1", items));
        }
        @Test void sixDimensionsAndConflictingSourcesArePersistedWithoutFormalLeakage() throws Exception {
            String sid = openedKnowledge("FORMAL");
            for (int round = 1; round <= 3; round++) {
                String id = answer(sid, "six_evidence_" + round, round, sixDraft(sid, true));
                assertThat(store.get(owner, id).status()).isEqualTo("SUCCEEDED");
                if (round < 3) assertThat(data(performGet("/api/interviews/sessions/" + sid, token)).path("agent").isNull()).isTrue();
            }
            var saved = data(performGet("/api/interviews/sessions/" + sid, token));
            var detail = saved.path("agent").path("evaluations").get(0).path("assessment").path("detail");
            assertThat(detail.path("items").size()).isEqualTo(6);
            assertThat(detail.path("items").get(1).path("claim").path("status").asText()).isEqualTo("DISPUTED");
            assertThat(detail.path("items").get(1).path("technicalReview").asText()).isEqualTo("REVIEW_REQUIRED");
            var sourceTypes = new ArrayList<String>();
            detail.path("evidence").forEach(e -> sourceTypes.add(e.path("sourceType").asText()));
            assertThat(sourceTypes).contains("KNOWLEDGE_EXCERPT", "ANSWER_SELF_REPORT");
            var replay = performPost("/api/interviews/sessions/" + sid + "/answer-commands",
                    Map.of("requestId", "six_evidence_1", "expectedTurn", 1, "answer", ANSWER), token);
            assertThat(data(replay).path("status").asText()).isEqualTo("SUCCEEDED");
            assertThat(data(performGet("/api/interviews/sessions/" + sid, token))).isEqualTo(saved);
            java.nio.file.Files.writeString(java.nio.file.Path.of("target/p1-assessment-session.json"), saved.toPrettyString());
        }
        @Test void unsupportedTechnicalCorrectionUsesOneRepairAndDoesNotCommit() throws Exception {
            String sid = openedKnowledge("TEACHING");
            String id = answer(sid, "ungrounded_technical_001", 1, sixDraft(sid, false));
            assertThat(store.get(owner, id).status()).isEqualTo("FAILED");
            assertThat(sessions.findById(owner, sid).orElseThrow().turns()).noneMatch(t -> "user".equals(t.speaker()));
            verify(client, times(3)).complete(any());
        }

    }

    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "interview.runs.worker-enabled=false", "ai.provider=deepseek", "interview.runs.decision-protocol=phased-v1" })
    @Nested
    class Decisions extends InterviewRunScenario {
        @MockitoBean ToolCallingChatClient client;
        @Autowired InterviewRunModel model;
        @Autowired InterviewSessionRepository sessions;
        @Autowired InterviewStateMachine machine;
        @Autowired JdbcTemplate jdbc;

        @BeforeEach void setup() throws Exception {
            when(client.configuration()).thenReturn(new ToolCallingChatClient.Config("local-fixture", "disabled", "v1"));
        }
        String start() throws Exception {
            return acceptStart(Map.of("targetRole", "Java 后端", "resumeSummary", "使用 Redis 保存幂等键，异常处理未描述。", "totalRounds", 3));
        }
        ToolCallingChatClient.Response text(String content) {
            return new ToolCallingChatClient.Response(objectMapper.createObjectNode().put("role","assistant").put("content",content),
                    "stop", new ModelUsage(ModelUsage.Status.REPORTED, 10L, 5L, 15L));
        }
        ToolCallingChatClient.Response action() {
            return text("{\"version\":\"phased-v1\",\"phase\":\"ACTION\",\"action\":\"COMPLETE\",\"reason\":\"已有资料足够\"}");
        }
        ToolCallingChatClient.Response finalOutput() {
            return text("{\"version\":\"phased-v1\",\"phase\":\"FINAL\",\"result\":{\"question\":\"请说明你在相关经历中承担的个人职责？\",\"questionCompetencyId\":\"experience\"}}");
        }
        ToolCallingChatClient.Response tool(String id) {
            var message = objectMapper.createObjectNode().put("role","assistant").putNull("content");
            message.putArray("tool_calls").addObject().put("id",id).put("type","function").putObject("function")
                    .put("name","read_material_section").put("arguments","{\"sectionId\":\"resume-0\",\"maxChars\":2000}");
            return new ToolCallingChatClient.Response(message, "tool_calls", ModelUsage.unknown(ModelUsage.Status.MISSING));
        }
        int count(String table, String id) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE run_id=?", Integer.class, id); }

        private tools.jackson.databind.node.ObjectNode modelCheckpoint(InterviewRunStore.Claim claim, String phase) {
            var cp = objectMapper.createObjectNode().put("stage","MODEL").put("outputPhase",phase)
                    .put("repaired",false).put("actionRepaired",false);
            cp.putArray("sources");
            cp.putArray("messages").addObject().put("role","system").put("content","fixture");
            store.checkpoint(claim,cp.toString(),"MODEL",null,null);
            return cp;
        }

        @Test void durableAdmissionCannotBorrowFinalRequestsEvenWithoutWorker() throws Exception {
            String id = start(); var claim = store.claim(store.get(owner,id));
            var cp = modelCheckpoint(claim,"ACTION");
            store.reserveModelAttempt(claim, prepared("first")).id(); store.reserveModelAttempt(claim, prepared("second")).id();
            assertThatThrownBy(() -> store.reserveModelAttempt(claim, prepared("borrow-final")).id()).hasMessage("RUN_BUDGET");
            assertThat(store.get(owner,id).attempts()).isEqualTo(2);
            cp.put("outputPhase","FINAL");
            store.checkpoint(claim,cp.toString(),"MODEL",null,null);
            var finalAttempt = store.reserveModelAttempt(claim, prepared("final"));
            assertThat(finalAttempt.timeoutMillis()).isBetween(1_000L,25_000L);
            // FINAL 在发送前已预占或结果为 UNKNOWN 时，重放不能再获得一次新的 FINAL 额度。
            assertThatThrownBy(() -> store.reserveModelAttempt(claim, prepared("repeat-final")).id()).hasMessage("RUN_BUDGET");
            cp.put("repaired",true);
            store.repair(claim,cp.toString());
            store.reserveModelAttempt(claim, prepared("repair")).id();
            assertThatThrownBy(() -> store.reserveModelAttempt(claim, prepared("fifth")).id()).hasMessage("RUN_BUDGET");
            assertThat(jdbc.queryForList("SELECT budget_phase FROM interview_model_attempts WHERE run_id=? ORDER BY ordinal",
                    String.class,id)).containsExactly("ACTION","ACTION","FINAL","FINAL_REPAIR");
            assertThat(jdbc.queryForObject("SELECT used_units FROM request_quotas WHERE scope_key=?",Integer.class,
                    InterviewRunStore.sha("ai-day:"+owner))).isEqualTo(3);
            verify(client,never()).complete(any());
        }

        @Test void concurrentReservationsHaveOnlyOneWinnerForLastActionSlot() throws Exception {
            String id = start(); var claim = store.claim(store.get(owner,id));
            modelCheckpoint(claim,"ACTION"); store.reserveModelAttempt(claim, prepared("first")).id();
            var barrier = new java.util.concurrent.CountDownLatch(1);
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var pending = new java.util.ArrayList<java.util.concurrent.Future<String>>();
                for (int i=0; i<2; i++) pending.add(executor.submit(() -> {
                    barrier.await();
                    try { store.reserveModelAttempt(claim, prepared("last-action")).id(); return "accepted"; }
                    catch (cn.edu.whu.zhizhida.common.execution.RunFailureException ex) { return ex.getMessage(); }
                }));
                barrier.countDown();
                var results = new java.util.ArrayList<String>();
                for (var future : pending) results.add(future.get(10, java.util.concurrent.TimeUnit.SECONDS));
                assertThat(results).containsExactlyInAnyOrder("accepted","RUN_BUDGET");
            }
            assertThat(count("interview_model_attempts",id)).isEqualTo(2);
        }

        @Test void missingPhaseForgedRepairAndChangedBudgetFailBeforeChargingAnAttempt() throws Exception {
            String id = start(); var claim = store.claim(store.get(owner,id));
            assertThatThrownBy(() -> store.reserveModelAttempt(claim, prepared("no-checkpoint")).id()).hasMessage("RUN_CHECKPOINT_INVALID");
            var cp = modelCheckpoint(claim,"FINAL");
            cp.put("repaired",true);
            store.checkpoint(claim,cp.toString(),"MODEL",null,null);
            assertThatThrownBy(() -> store.reserveModelAttempt(claim, prepared("forged-repair")).id()).hasMessage("RUN_CHECKPOINT_INVALID");
            var config = (tools.jackson.databind.node.ObjectNode)objectMapper.readTree(store.get(owner,id).config());
            config.withObject("budgetProfile").withObject("execution").put("maxModelAttempts", 40);
            jdbc.update("UPDATE interview_runs SET config_json=? WHERE id=?",config.toString(),id);
            assertThatThrownBy(() -> store.reserveModelAttempt(claim, prepared("forged-budget")).id()).hasMessage("MODEL_CONFIG_CHANGED");
            assertThat(count("interview_model_attempts",id)).isZero();
            assertThat(store.get(owner,id).attempts()).isZero();
        }

        @Test void databaseRejectsCallsWhenTheirFinalTimeReserveNoLongerFits() throws Exception {
            String id = start(); var claim = store.claim(store.get(owner,id));
            modelCheckpoint(claim,"ACTION");
            jdbc.update("UPDATE interview_runs SET deadline=TIMESTAMPADD(SECOND,55,CURRENT_TIMESTAMP(6)) WHERE id=?",id);
            assertThatThrownBy(() -> store.reserveModelAttempt(claim, prepared("late-action")).id()).hasMessage("RUN_BUDGET");
            modelCheckpoint(claim,"FINAL");
            jdbc.update("UPDATE interview_runs SET deadline=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)) WHERE id=?",id);
            assertThatThrownBy(() -> store.reserveModelAttempt(claim, prepared("late-final")).id()).hasMessage("RUN_BUDGET");
            assertThat(count("interview_model_attempts",id)).isZero();
        }

        @Test void unknownActionAfterCrashStillConsumesItsSlotAndKeepsTheBudgetSnapshot() throws Exception {
            String id = start(); var claim = store.claim(store.get(owner,id));
            modelCheckpoint(claim,"ACTION"); store.reserveModelAttempt(claim, prepared("unsent-or-unknown")).id();
            String frozen = store.get(owner,id).config();
            jdbc.update("UPDATE interview_runs SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",id);
            var next = store.claim(store.get(owner,id));
            store.reserveModelAttempt(next, prepared("last-action")).id();
            assertThatThrownBy(() -> store.reserveModelAttempt(next, prepared("extra-action")).id()).hasMessage("RUN_BUDGET");
            assertThatThrownBy(() -> store.reserveModelAttempt(claim, prepared("old-epoch")).id()).hasMessage("RUN_FENCED");
            assertThat(store.get(owner,id).config()).isEqualTo(frozen);
            assertThat(jdbc.queryForList("SELECT status FROM interview_model_attempts WHERE run_id=? ORDER BY ordinal",
                    String.class,id)).containsExactly("UNKNOWN","STARTED");
        }

        @Test void oldConfigurationWithoutSnapshotRetainsLegacyCountOnlyAdmission() throws Exception {
            String id = start();
            var old = (tools.jackson.databind.node.ObjectNode)objectMapper.readTree(store.get(owner,id).config());
            old.remove("budgetProfile");
            jdbc.update("UPDATE interview_runs SET config_json=? WHERE id=?",old.toString(),id);
            var claim = store.claim(store.get(owner,id));
            for (int i=0; i<4; i++) store.reserveModelAttempt(claim, prepared("legacy-"+i)).id();
            assertThatThrownBy(() -> store.reserveModelAttempt(claim, prepared("legacy-fifth")).id()).hasMessage("RUN_BUDGET");
            assertThat(jdbc.queryForList("SELECT budget_phase FROM interview_model_attempts WHERE run_id=?",
                    String.class,id)).containsOnly("LEGACY");
            assertThat(store.get(owner,id).config()).isEqualTo(old.toString());
        }

        @Test void modelChoosesZeroToolsAndCompleteIsOnlyAPhaseTransition() throws Exception {
            var calls = new AtomicInteger();
            when(client.complete(any())).thenAnswer(inv -> {
                ToolCallingChatClient.Request request = inv.getArgument(0);
                String system = request.messages().getFirst().path("content").asText();
                if (calls.incrementAndGet() == 1) {
                    assertThat(request.toolsAllowed()).isTrue();
                    assertThat(system).contains("ACTION").doesNotContain("rubric", "agentDecision", "finalSummary");
                    return action();
                }
                assertThat(request.toolsAllowed()).isFalse();
                assertThat(request.tools()).isEmpty();
                assertThat(system).contains("FINAL", "questionCompetencyId");
                return finalOutput();
            });
            String id = start(); run(id);
            assertThat(store.get(owner,id).status()).isEqualTo("SUCCEEDED");
            assertThat(store.get(owner,id).attempts()).isEqualTo(2);
            assertThat(store.get(owner,id).tools()).isZero();
            assertThat(count("interview_model_attempts",id)).isEqualTo(2);
            assertThat(objectMapper.writeValueAsString(store.events(owner,id,0))).doesNotContain("已有资料足够", "Redis");
        }

        @Test void twoActionsLeaveBothFinalAndRepairRequestsAvailableWithinB0() throws Exception {
            var calls = new AtomicInteger();
            when(client.complete(any())).thenAnswer(inv -> {
                ToolCallingChatClient.Request request = inv.getArgument(0);
                int n = calls.incrementAndGet();
                if (n <= 2) return tool("read-"+n);
                assertThat(request.toolsAllowed()).isFalse();
                assertThat(request.messages().toString()).contains("tool_call_id", "read-1", "read-2");
                return n == 3 ? action() : finalOutput(); // FINAL 阶段误返 ACTION 时须先修复，不能直接提交。
            });
            String id = start(); run(id);
            assertThat(store.get(owner,id).status()).isEqualTo("SUCCEEDED");
            assertThat(store.get(owner,id).attempts()).isEqualTo(4);
            assertThat(store.get(owner,id).repairs()).isEqualTo(1);
            assertThat(count("interview_tool_receipts",id)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT used_units FROM request_quotas WHERE scope_key=?",Integer.class,
                    InterviewRunStore.sha("ai-day:"+owner))).isEqualTo(3);
        }

        @Test void actionRepairDoesNotUseFinalRepairOrReopenTools() throws Exception {
            var calls = new AtomicInteger();
            when(client.complete(any())).thenAnswer(inv -> {
                ToolCallingChatClient.Request request = inv.getArgument(0);
                return switch(calls.incrementAndGet()) {
                    case 1 -> finalOutput(); // ACTION 阶段不能直接返回 FINAL。
                    case 2 -> { assertThat(request.toolsAllowed()).isFalse(); assertThat(request.messages().toString()).contains("只修正"); yield action(); }
                    case 3 -> text("{}");
                    default -> finalOutput();
                };
            });
            String id = start(); run(id);
            assertThat(store.get(owner,id).status()).isEqualTo("SUCCEEDED");
            assertThat(store.get(owner,id).attempts()).isEqualTo(4);
            assertThat(store.get(owner,id).repairs()).isEqualTo(1);
        }

        @Test void repeatedInvalidActionFailsWithoutConsumingFinalReserveOrCreatingQuestion() throws Exception {
            when(client.complete(any())).thenReturn(finalOutput());
            String id = start(); run(id);
            assertThat(store.get(owner,id).error()).isEqualTo("MODEL_ACTION_INVALID");
            assertThat(store.get(owner,id).attempts()).isEqualTo(2);
            assertThat(store.get(owner,id).repairs()).isZero();
        }

        @Test void repeatedInvalidFinalNeverExceedsFourAttempts() throws Exception {
            when(client.complete(any())).thenReturn(tool("one"), tool("two"), text("{}"));
            String id = start(); run(id);
            assertThat(store.get(owner,id).error()).isEqualTo("MODEL_OUTPUT_INVALID");
            assertThat(store.get(owner,id).attempts()).isEqualTo(4);
            assertThat(store.get(owner,id).repairs()).isEqualTo(1);
        }

        @Test void finalToolsAreRejectedButReturnedUsageIsStillSaved() throws Exception {
            when(client.complete(any())).thenReturn(action(),tool("forbidden"));
            String id = start(); run(id);
            assertThat(store.get(owner,id).error()).isEqualTo("TOOL_DISABLED");
            assertThat(count("interview_tool_receipts",id)).isZero();
            assertThat(jdbc.queryForList("SELECT status FROM interview_model_attempts WHERE run_id=?",String.class,id))
                    .containsOnly("SUCCEEDED");
        }

        @Test void savedActionResumesIntoFinalWithoutRepeatingTheActionCall() throws Exception {
            String id = saveResponse("ACTION_VALIDATE", "ACTION", action());
            when(client.complete(any())).thenReturn(finalOutput());
            run(id);
            assertThat(store.get(owner,id).status()).isEqualTo("SUCCEEDED");
            verify(client,times(1)).complete(any());
            assertThat(store.get(owner,id).attempts()).isEqualTo(2);
        }

        @Test void savedFinalCommitsWithoutAnyNewModelCall() throws Exception {
            String id = saveResponse("VALIDATE", "FINAL", finalOutput());
            run(id);
            assertThat(store.get(owner,id).status()).isEqualTo("SUCCEEDED");
            verify(client,never()).complete(any());
        }

        @Test void missingCheckpointPhaseCannotDowngradeFrozenContract() throws Exception {
            String id = saveResponse("VALIDATE", "FINAL", finalOutput());
            var cp = (tools.jackson.databind.node.ObjectNode)objectMapper.readTree(store.get(owner,id).checkpoint());
            cp.remove("outputPhase");
            jdbc.update("UPDATE interview_runs SET checkpoint_json=? WHERE id=?",cp.toString(),id);
            run(id);
            assertThat(store.get(owner,id).error()).isEqualTo("RUN_CHECKPOINT_INVALID");
            verify(client,never()).complete(any());
        }

        @Test void shortDeadlineSkipsActionsAndPreservesFinalRepairTime() throws Exception {
            when(client.complete(any())).thenAnswer(inv -> {
                ToolCallingChatClient.Request request = inv.getArgument(0);
                assertThat(request.toolsAllowed()).isFalse();
                assertThat(request.timeout().toMillis()).isBetween(1_000L,20_000L);
                return finalOutput();
            });
            String id = start();
            jdbc.update("UPDATE interview_runs SET deadline=TIMESTAMPADD(SECOND,50,CURRENT_TIMESTAMP(6)) WHERE id=?",id);
            run(id);
            assertThat(store.get(owner,id).status()).isEqualTo("SUCCEEDED");
            assertThat(store.get(owner,id).attempts()).isEqualTo(1);
        }

        @Test void insufficientFinalReserveMakesNoProviderRequest() throws Exception {
            String id = start();
            jdbc.update("UPDATE interview_runs SET deadline=TIMESTAMPADD(SECOND,28,CURRENT_TIMESTAMP(6)) WHERE id=?",id);
            run(id);
            assertThat(store.get(owner,id).error()).isEqualTo("RUN_BUDGET");
            verify(client,never()).complete(any());
        }

        @Test void finalTransportFailureDoesNotSpendTheStructuralRepairReserveOnRetry() throws Exception {
            when(client.complete(any())).thenReturn(action()).thenThrow(cn.edu.whu.zhizhida.common.UpstreamServiceException.coded("MODEL_HTTP_503"));
            String id = start(); run(id);
            assertThat(store.get(owner,id).error()).isEqualTo("MODEL_HTTP_503");
            assertThat(store.get(owner,id).attempts()).isEqualTo(2);
        }

        @Test void reviewEnvelopeKeepsAssessmentProjectionAndChargesOnlyOneAnswerAdmission() throws Exception {
            when(client.complete(any())).thenReturn(action(), finalOutput());
            String opening = start(); run(opening);
            var session = sessions.findById(owner,store.get(owner,opening).sessionId()).orElseThrow();
            String key = UUID.randomUUID().toString();
            String answer = "我使用 Redis 保存幂等请求号；数据库异常后状态尚未验证。";
            var pending = (cn.edu.whu.zhizhida.interview.domain.InterviewTransitions.PendingAnswer)
                    new cn.edu.whu.zhizhida.interview.domain.InterviewTransitions().answer(session,answer,key,1);
            String context = machine.runContext(session,pending,java.util.List.of());
            var result = new MockInterviewAiService(objectMapper,cn.edu.whu.zhizhida.observability.BusinessMetrics.disabled())
                    .generateReview(session.targetRole(),context,pending.turns(),pending.answeredCount(),pending.finalRound());
            var envelope = objectMapper.createObjectNode().put("version","phased-v1").put("phase","FINAL").set("result",result);
            when(client.complete(any())).thenReturn(action(), text(envelope.toString()));
            var accepted = performPost("/api/interviews/sessions/"+session.sessionId()+"/answer-commands",
                    Map.of("requestId",key,"expectedTurn",1,"answer",answer),token);
            String id = (String)dataMap(accepted).get("runId"); run(id);
            assertThat(store.get(owner,id).status()).isEqualTo("SUCCEEDED");
            var updated = sessions.findById(owner,session.sessionId()).orElseThrow();
            assertThat(updated.turnCount()).isEqualTo(2);
            assertThat(updated.agent().evaluations()).hasSize(1);
            assertThat(objectMapper.writeValueAsString(updated)).doesNotContain("phased-v1", "\"phase\":\"ACTION\"");
            assertThat(jdbc.queryForObject("SELECT used_units FROM request_quotas WHERE scope_key=?",Integer.class,
                    InterviewRunStore.sha("ai-day:"+owner))).isEqualTo(5);
        }

        @Test void invalidReviewDoesNotAdvanceFormalTurnEvenWithValidEnvelope() throws Exception {
            when(client.complete(any())).thenReturn(action(), finalOutput());
            String opening = start(); run(opening);
            String sessionId = store.get(owner,opening).sessionId();
            var before = sessions.findById(owner,sessionId).orElseThrow();
            when(client.complete(any())).thenReturn(action(),text("{\"version\":\"phased-v1\",\"phase\":\"FINAL\",\"result\":{}}"));
            var accepted = performPost("/api/interviews/sessions/"+sessionId+"/answer-commands",Map.of("requestId",UUID.randomUUID().toString(),
                    "expectedTurn",1,"answer","尚未核实异常处理。"),token);
            String id = (String)dataMap(accepted).get("runId"); run(id);
            assertThat(store.get(owner,id).error()).isEqualTo("MODEL_OUTPUT_INVALID");
            var after = sessions.findById(owner,sessionId).orElseThrow();
            assertThat(after.turnCount()).isEqualTo(before.turnCount());
            assertThat(after.turns()).isEqualTo(before.turns());
            assertThat(after.agent().evaluations()).isEmpty();
        }

        private String saveResponse(String stage, String phase, ToolCallingChatClient.Response response) throws Exception {
            String id = start();
            var claim = store.claim(store.get(owner,id));
            var cp = objectMapper.createObjectNode().put("stage","MODEL").put("outputPhase",phase)
                    .put("repaired",false).put("actionRepaired",false);
            cp.putArray("sources");
            cp.putArray("messages").addObject().put("role","system").put("content","fixture");
            store.checkpoint(claim,cp.toString(),"MODEL",null,null);
            String attempt = store.reserveModelAttempt(claim, prepared("fixture")).id();
            cp.put("stage",stage);
            cp.withArray("messages").add(response.message());
            cp.set("response",response.message());
            store.response(claim,attempt,objectMapper.writeValueAsString(response),"{}",1,cp.toString(),stage);
            jdbc.update("UPDATE interview_runs SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",id);
            return id;
        }
    }

    /**
     * 显式启用后，仅以合成数据运行生产工作器、校验器、工具及调用台账。
     */
    @EnabledIfEnvironmentVariable(named = "RUN_REAL_AGENT_EVAL", matches = "true")
    @org.springframework.test.context.ActiveProfiles("agent-evaluation")
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "interview.runs.worker-enabled=false", "ai.provider=deepseek" })
    @Nested
    class RealEvaluation extends IntegrationTestBase {
        @DynamicPropertySource
        static void provider(DynamicPropertyRegistry r) {
            r.add("ai.api-key",
                    () -> System.getenv().getOrDefault("DEEPSEEK_API_KEY", System.getenv().getOrDefault("AI_API_KEY", "")));
            r.add("ai.deepseek.base-url",
                    () -> System.getenv().getOrDefault("DEEPSEEK_BASE_URL", "https://api.deepseek.com"));
            r.add("ai.deepseek.model", () -> System.getenv().getOrDefault("DEEPSEEK_MODEL", "deepseek-chat"));
            r.add("ai.deepseek.thinking", () -> System.getenv().getOrDefault("DEEPSEEK_THINKING", "disabled"));
        }

        @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
        InterviewRunStore store;
        @Autowired
        InterviewRunWorker worker;
        @Autowired
        InterviewSessionRepository sessions;
        @Autowired
        InterviewRunModel model;
        @Autowired
        JdbcTemplate jdbc;

        @Test
        void evaluateWithTheSameRunnerAndSyntheticSources() throws Exception {
            Path directory=Path.of("../docs/evidence").toAbsolutePath().normalize();
            String name=System.getenv().getOrDefault("RUN_EVAL_REPORT","agent-evaluation-"+UUID.randomUUID()+".jsonl");
            assertThat(name).matches("[A-Za-z0-9_-]+\\.jsonl");
            Path report=directory.resolve(name);
            Files.writeString(report, "", java.nio.file.StandardOpenOption.CREATE_NEW);
            String route = System.getenv().getOrDefault("RUN_EVAL_ROUTE", "TOOL");
            assertThat(route).isIn("LEGACY", "FIXED", "TOOL");
            String token = registerUniqueUserToken("password123", "合成评测");
            long owner = objectMapper.readTree(performGet("/api/users/me", token).getResponse().getContentAsString())
                    .path("data").path("id").asLong();
            var cases = objectMapper.readTree(getClass().getResourceAsStream("/agent-evaluation.json"));
            String ids = System.getenv().getOrDefault("RUN_EVAL_IDS", "redis-order,adequate-owner");
            boolean withholdExcerpts = Boolean.parseBoolean(System.getenv("RUN_EVAL_WITHHOLD_EXCERPTS"));
            if (withholdExcerpts) {
                assertThat(ids).isEqualTo("redis-order");
                assertThat(route).isEqualTo("TOOL");
            }
            if (System.getenv("RUN_EVAL_MAX_ATTEMPTS") != null) {
                var config = objectMapper.readTree(model.configuration());
                var budget = cn.edu.whu.zhizhida.interview.execution.BudgetProfile.frozen(objectMapper, config);
                assertThat(ids.split(",")).hasSize(1);
                int limit = Integer.parseInt(System.getenv("RUN_EVAL_MAX_ATTEMPTS"));
                if (withholdExcerpts) {
                    assertThat(limit).isBetween(1, 4);
                    // 仅测试入口可追加更小的外部额度。在持久化 Attempt 之前拒绝，避免第四次实际发送。
                    var reservations = new java.util.concurrent.atomic.AtomicInteger();
                    org.mockito.Mockito.doAnswer(invocation -> {
                        if (reservations.incrementAndGet() > limit)
                            throw new cn.edu.whu.zhizhida.common.execution.RunFailureException("RUN_BUDGET");
                        return invocation.callRealMethod();
                    }).when(store).reserveModelAttempt(org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(cn.edu.whu.zhizhida.interview.execution.RunContextAssembler.Prepared.class));
                } else {
                    assertThat(budget.execution().maxModelAttempts()).isLessThanOrEqualTo(limit);
                }
                assertThat(config.at("/executionProfile/maxOutputTokens").asInt()).isBetween(1, 2000);
            }
            var outputs = new ArrayList<JsonNode>();
            try {
                for (var fixture : cases) {
                    String id = fixture.path("id").asText();
                    if (!Set.of(ids.split(",")).contains(id))
                        continue;
                    assertThat(fixture.path("partition").asText()).as("Heldout evaluation requires a separate frozen release process")
                            .isIn("development","validation");
                    var saved=performPost("/api/knowledge/documents",Map.of("title",fixture.path("title").asText(),"content",fixture.path("material").asText()),token);
                    assertThat(saved.getResponse().getStatus()).isEqualTo(200);
                    var document=objectMapper.readTree(saved.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
                    String source=document.path("content").asText();
                    var excerpts=new ArrayList<KnowledgeSnapshot.Excerpt>();
                    for(var chunk:document.path("chunks")) excerpts.add(new KnowledgeSnapshot.Excerpt(chunk.path("id").asText(),chunk.path("heading").asText(),chunk.path("startLine").asInt(),chunk.path("endLine").asInt(),chunk.path("content").asText()));
                    var doc = new KnowledgeSnapshot(document.path("id").asText(), document.path("title").asText(),
                            document.path("sourceName").asText(), document.path("version").asInt(), InterviewRunStore.sha(source),
                            source, withholdExcerpts ? List.of() : excerpts);
                    var policy = new InterviewAgentPolicy();
                    var agent = policy.plan(InterviewMode.GENERAL, 6,
                            new InterviewPlanningContext("Java 后端", List.of(), List.of(), List.of(), "合成练习项目"), "deepseek",
                            List.of());
                    agent = new InterviewAgentState(agent.version(), agent.executionMode(), agent.plan(),
                            agent.activeCompetencyId(), agent.evaluations(), agent.digest(), agent.practiceMemory(),
                            new InterviewTraining(null, null, null, List.of(doc)));
                    String sid = UUID.randomUUID().toString();
                    var fixed = new InterviewSession(sid, "Java 后端", null, "合成练习项目：" + fixture.path("title").asText(), null,
                            null, "Java 后端", InterviewMode.GENERAL, 6, null, InterviewState.WAITING_ANSWER, 1,
                            List.of(new InterviewTurn("interviewer", fixture.path("question").asText())), null, agent,
                            InterviewDifficulty.STANDARD);
                    sessions.save(owner, fixed);
                    var answer = new InterviewRun.Answer(UUID.randomUUID().toString(), 1, fixture.path("answer").asText());
                    var input = objectMapper.createObjectNode();
                    input.set("answer", objectMapper.valueToTree(answer));
                    input.put("sessionHash", InterviewRunStore.sha(objectMapper.writeValueAsString(fixed)));
                    var run = store.accept(owner, "ANSWER", answer.requestId(), InterviewRunStore.sha(input.toString()),
                            sid, 1,
                            () -> new InterviewRunStore.Fixed(sid, input.toString(), model.configuration(), route));
                    long started = System.nanoTime();
                    // 手动领取并运行同一生产工作器，避免后台调度与计时过程竞争。
                    while (!store.get(owner, run.id()).terminal()) {
                        var claim = store.claim(store.get(owner, run.id()));
                        if (claim != null) worker.execute(claim);
                        else Thread.sleep(100);
                        assertThat((System.nanoTime() - started) / 1000000).as("Worker deadline").isLessThan(100_000L);
                    }
                    long elapsed = (System.nanoTime() - started) / 1000000;
                    var done = store.get(owner, run.id());
                    var output = objectMapper.createObjectNode().put("caseId", id)
                            .put("family", fixture.path("family").asText())
                            .put("partition", fixture.path("partition").asText())
                            .put("route", route).put("runId", done.id()).put("status", done.status())
                            .put("error", done.error()).put("elapsedMs", elapsed)
                            .put("attempts", done.attempts()).put("toolExecutions", done.tools())
                            .put("repairs", done.repairs()).put("inputHash", done.inputHash())
                            .put("sourceHash", doc.sha256()).put("humanReviewRequired", true);
                    output.set("config", objectMapper.readTree(done.config()));
                    var checkpoint=done.checkpoint()==null?objectMapper.createObjectNode():objectMapper.readTree(done.checkpoint());
                    var validation=output.putArray("validationFailures");
                    for(var message:checkpoint.path("messages")) if(message.path("role").asText().equals("user")&&message.path("content").asText().startsWith("服务端校验未通过：")) validation.add(message.path("content").asText());
                    output.put("lastPublicCandidate",checkpoint.path("response").path("content").asText());
                    output.set("fixture", fixture);
                    if (withholdExcerpts) output.put("initialKnowledgeExcerpts", "WITHHELD");
                    output.set("attemptLedger", objectMapper.valueToTree(jdbc.queryForList(
                            "SELECT ordinal,status,error_code,usage_json,elapsed_ms FROM interview_model_attempts WHERE run_id=? ORDER BY ordinal",
                            done.id())));
                    var trace = output.putArray("modelToolRequests");
                    var responses = output.putArray("attemptResponses");
                    for (String response : jdbc.queryForList(
                            "SELECT response_json FROM interview_model_attempts WHERE run_id=? AND response_json IS NOT NULL ORDER BY ordinal",
                            String.class, done.id())) {
                        var savedResponse = objectMapper.readTree(response);
                        var calls = savedResponse.path("message").path("tool_calls");
                        responses.addObject().put("finish", savedResponse.path("finish").asText())
                                .put("toolCallCount", calls.isArray() ? calls.size() : 0);
                        if (calls.isArray())
                            trace.addAll((tools.jackson.databind.node.ArrayNode) calls);
                    }
                    output.set("receipts", objectMapper.valueToTree(jdbc.queryForList(
                            "SELECT tool_name,args_json,source_hash,result_json,execution_kind FROM interview_tool_receipts WHERE run_id=? ORDER BY id",
                            done.id())));
                    if (done.status().equals("SUCCEEDED")) {
                        var completed = sessions.findById(owner, sid).orElseThrow();
                        output.set("evaluation", objectMapper.valueToTree(completed.agent().evaluations().getFirst()));
                        output.put("nextQuestion", completed.turns().getLast().content());
                    }
                    // 失败样本同样进入报告，只有提交成功的运行才附带通过校验的评估。
                    outputs.add(output);
                    Files.writeString(report, output + "\n", java.nio.file.StandardOpenOption.APPEND);
                    System.out.println("[agent-evaluation] " + route + " " + id + " status=" + done.status() + " attempts="
                            + done.attempts() + " tools=" + done.tools() + " elapsedMs=" + elapsed);
                }
                assertThat(outputs).isNotEmpty();
                assertThat(outputs.stream().filter(r -> r.path("status").asText().equals("SUCCEEDED")).count())
                        .as("At least one real run must succeed; failures remain in the report").isPositive();
            } finally {
                // 注销账号前记录持久化调用次数，供预算脚本区分零调用失败与未知消耗。
                int attempts=jdbc.queryForObject("SELECT COUNT(*) FROM interview_model_attempts a JOIN interview_runs r ON r.id=a.run_id WHERE r.owner_id=?",Integer.class,owner);
                Files.writeString(directory.resolve(name+".audit.json"),objectMapper.writeValueAsString(Map.of("totalAttempts",attempts,"source","durable attempt ledger before account cleanup")),java.nio.file.StandardOpenOption.CREATE_NEW);
                performDeleteWithBody("/api/users/me", Map.of("currentPassword", "password123"), token);
            }
        }
    }

    /** 只替换模型传输边界，真实受理、引用校验、有限修正和原子提交全部保留。 */
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "interview.runs.worker-enabled=false", "ai.provider=deepseek", "interview.runs.loop-policy=B0",
            "interview.runs.decision-protocol=legacy",
            "interview.agent.adaptive-routing-enabled=false",
            "learning.practice-tools-enabled=false" })
    @Nested
    class RecordedReplay extends IntegrationTestBase {
        // 冻结录制版和现行版的差异，其余系统指令、来源与工具定义仍逐项核对。
        private static final String RECORDED_RETRIEVAL_RULE = "只在现有证据不足时调用工具；允许零工具完成。";
        private static final String CURRENT_RETRIEVAL_RULE = "问题涉及本项目的具体做法或验证依据时，"
                + "若回答只给出结论，且目录有相关资料而上下文缺少原文，在工具可用时先用 search_knowledge 核对所选知识资料，"
                + "或用 read_material_section 核对材料片段。没有取得相关原文时保留未知，不得把通用经验当作项目事实。"
                + "回答和已有原文足以判断时，允许零工具完成。";
        @MockitoBean ToolCallingChatClient client;
        @Autowired InterviewRunStore store;
        @Autowired InterviewRunWorker worker;
        @Autowired InterviewRunModel model;
        @Autowired InterviewSessionRepository sessions;
        @Autowired KnowledgeRepository knowledge;

        @ParameterizedTest(name = "R3 recorded Run {0}")
        @ValueSource(ints = { 0, 1 })
        void originalResponsesReplayThroughWorkerWithUpdatedRetrievalRuleAndFixedSources(int sample) throws Exception {
            var recorded = CloseoutRecordedFixtures.report().get(sample);
            var exchanges = CloseoutRecordedFixtures.exchange(sample);
            var firstRequest = objectMapper.readTree(exchanges.getFirst().path("body").asText());
            var prompt = CloseoutRecordedFixtures.prompt(firstRequest);
            var fixture = recorded.path("fixture");
            var config = recorded.path("config");
            when(client.configuration()).thenReturn(objectMapper.treeToValue(config.path("model"), ToolCallingChatClient.Config.class));
            var execution = new java.util.TreeMap<String, String>();
            config.path("executionProfile").properties().forEach(entry -> execution.put(entry.getKey(), entry.getValue().asText()));
            when(client.executionProfile()).thenReturn(execution);
            var frozen = objectMapper.readTree(model.configuration());
            // 历史哈希受 Map.of 的跨 JVM 键序影响；原字节另验 SHA，以下逐条核对实际提示词与工具定义。
            assertThat(config.has("fingerprintFormat")).isFalse();
            assertThat(frozen.path("fingerprintFormat").asText()).isEqualTo("sorted-json-v1");
            for (String field : List.of("prompt", "citationProtocol", "validator", "toolset"))
                assertThat(frozen.path(field)).as(field).isEqualTo(config.path(field));

            String token = registerUniqueUserToken("password123", "原始录制回放");
            long owner = objectMapper.readTree(performGet("/api/users/me", token).getResponse().getContentAsString()).path("data").path("id").asLong();
            try {
                // 仅在一次性测试账号下重建原资料 ID 与版本；模型响应及其 anchor 不做转换。
                String docId = prompt.path("untrustedEvidence").path("assessmentSources").get(0).path("sourceKey").asText().split("/")[1];
                String content = fixture.path("material").asText(), title = fixture.path("title").asText();
                var chunks = KnowledgeText.split(docId, 1, content);
                knowledge.insert(owner, new KnowledgeDocument(docId, title, "合成录制", 1, content, Instant.EPOCH, chunks));
                var snapshot = new KnowledgeSnapshot(docId, title, "合成录制", 1, InterviewRunStore.sha(content), content,
                        chunks.stream().map(c -> new KnowledgeSnapshot.Excerpt(c.id(), c.heading(), c.startLine(), c.endLine(), c.content())).toList());
                var planned = new InterviewAgentPolicy().plan(InterviewMode.GENERAL, 6,
                        new InterviewPlanningContext("Java 后端", List.of(), List.of(), List.of(), "合成练习项目"), "deepseek", List.of());
                var agent = new InterviewAgentState(planned.version(), planned.executionMode(), planned.plan(), planned.activeCompetencyId(),
                        planned.evaluations(), planned.digest(), planned.practiceMemory(), new InterviewTraining(null, null, null, List.of(snapshot)));
                String sid = UUID.randomUUID().toString();
                var session = new InterviewSession(sid, "Java 后端", null, "合成练习项目：" + title, null, null, "Java 后端",
                        InterviewMode.GENERAL, 6, null, InterviewState.WAITING_ANSWER, 1,
                        List.of(new InterviewTurn("interviewer", fixture.path("question").asText())), null, agent, InterviewDifficulty.STANDARD);
                sessions.save(owner, session);
                var calls = new AtomicInteger();
                when(client.complete(any())).thenAnswer(invocation -> {
                    int ordinal = calls.getAndIncrement();
                    assertThat(ordinal).isLessThan(exchanges.size() / 2);
                    var expectedRequest = objectMapper.readTree(exchanges.get(ordinal * 2).path("body").asText());
                    ToolCallingChatClient.Request request = invocation.getArgument(0);
                    assertThat(request.toolsAllowed()).isEqualTo(ordinal == 0);
                    assertThat(objectMapper.<JsonNode>valueToTree(request.tools())).isEqualTo(expectedRequest.path("tools"));
                    assertThat(request.messages()).hasSize(expectedRequest.path("messages").size());
                    for (int i = 0; i < request.messages().size(); i++) {
                        var expected = expectedRequest.path("messages").get(i);
                        assertThat(request.messages().get(i).path("role")).isEqualTo(expected.path("role"));
                        if (i == 0) {
                            String system = expected.path("content").asText();
                            assertThat(system).containsOnlyOnce(RECORDED_RETRIEVAL_RULE);
                            assertThat(request.messages().get(i).path("content").asText())
                                    .isEqualTo(system.replace(RECORDED_RETRIEVAL_RULE, CURRENT_RETRIEVAL_RULE));
                        } else if (i == 1) {
                            // JSON 键序不影响语义；仅移除录制 V3 中恒空的退役 V4 字段，其余值完整比较。
                            var actual = request.messages().get(i).path("content").asText().split("\n本场证据目录：", 2);
                            var saved = expected.path("content").asText().split("\n本场证据目录：", 2);
                            assertThat(actual).hasSize(2);
                            var actualInput = objectMapper.readTree(actual[0]);
                            var savedInput = objectMapper.readTree(saved[0]);
                            assertThat(savedInput.path("agentCheckpoint").path("version").asText()).isEqualTo("interview-agent-v3");
                            assertThat(savedInput.path("untrustedEvidence").path("previousIntents")).isEqualTo(objectMapper.createArrayNode());
                            assertThat(actualInput.path("untrustedEvidence").has("previousIntents")).isFalse();
                            ((tools.jackson.databind.node.ObjectNode) savedInput.path("untrustedEvidence")).remove("previousIntents");
                            assertThat(actualInput).isEqualTo(savedInput);
                            assertThat(objectMapper.readTree(actual[1])).isEqualTo(objectMapper.readTree(saved[1]));
                        } else assertThat(request.messages().get(i)).isEqualTo(expected);
                    }
                    var response = objectMapper.readTree(exchanges.get(ordinal * 2 + 1).path("body").asText());
                    var choice = response.path("choices").get(0);
                    var usage = response.path("usage");
                    return new ToolCallingChatClient.Response(choice.path("message"), choice.path("finish_reason").asText(),
                            new ModelUsage(ModelUsage.Status.REPORTED, usage.path("prompt_tokens").asLong(),
                                    usage.path("completion_tokens").asLong(), usage.path("total_tokens").asLong()));
                });
                String path = "/api/interviews/sessions/" + sid + "/answer-commands";
                var body = Map.of("requestId", prompt.path("transcript").get(1).path("requestId").asText(),
                        "expectedTurn", 1, "answer", fixture.path("answer").asText());
                var accepted = performPost(path, body, token);
                assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
                String runId = data(accepted).path("runId").asText();
                worker.execute(store.claim(store.get(owner, runId)));
                var done = store.get(owner, runId);
                assertThat(done.status()).isEqualTo("SUCCEEDED");
                assertThat(done.attempts()).isEqualTo(sample == 0 ? 1 : 2);
                assertThat(done.repairs()).isEqualTo(sample);
                assertThat(done.tools()).isZero(); // 保留第一批缺少必需工具的事实，回放成功不代表批次 PASS。
                if (sample == 1) assertThat(done.checkpoint()).contains("SEMANTIC_MISMATCH");
                var committed = sessions.findById(owner, sid).orElseThrow();
                assertThat(committed.turnCount()).isEqualTo(2);
                assertThat(committed.agent().evaluations()).hasSize(1);
                var evaluation = objectMapper.valueToTree(committed.agent().evaluations().getFirst());
                assertThat(evaluation.path("decision")).isEqualTo(recorded.path("evaluation").path("decision"));
                var evidence = evaluation.path("assessment").path("detail").path("evidence");
                var originalEvidence = recorded.path("evaluation").path("assessment").path("detail").path("evidence");
                assertThat(evidence.size()).isEqualTo(originalEvidence.size());
                for (int i = 0; i < evidence.size(); i++) for (String field : List.of("quote", "offsetStart", "offsetEnd", "sourceVersion", "sourceHash"))
                    assertThat(evidence.get(i).path(field)).as(field).isEqualTo(originalEvidence.get(i).path(field));
                assertThat(data(performPost(path, body, token)).path("runId").asText()).isEqualTo(runId);
                assertThat(calls.get()).isEqualTo(sample == 0 ? 1 : 2);
                assertThat(sessions.findById(owner, sid).orElseThrow().turnCount()).isEqualTo(2);
            } finally {
                performDeleteWithBody("/api/users/me", Map.of("currentPassword", "password123"), token);
            }
        }

        private JsonNode data(org.springframework.test.web.servlet.MvcResult result) throws Exception {
            return objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
        }
    }
}
