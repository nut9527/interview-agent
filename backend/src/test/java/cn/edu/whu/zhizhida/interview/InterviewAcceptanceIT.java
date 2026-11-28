package cn.edu.whu.zhizhida.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.common.ErrorCode;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunStore;
import cn.edu.whu.zhizhida.interview.execution.RunAdmission;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class InterviewAcceptanceIT {

        @Nested
        class Sessions extends InterviewScenario {
                @Test
                void createsReadsAnswersListsAndDeletesDefaultSession() throws Exception {
                        String token = account("面试用户");
                        var session = createSession(token);
                        String sessionId = session.path("sessionId").asString();
                        String url = "/api/interviews/sessions/" + sessionId;
                        assertThat(sessionId).isNotBlank();
                        assertThat(session.path("targetRole").asString()).isEqualTo("Java开发工程师");
                        assertThat(session.path("state").asString()).isEqualTo("WAITING_ANSWER");
                        assertThat(session.path("turns")).isNotEmpty();
                        assertThat(data(performGet(url, token))).isEqualTo(session);

                        var answered = data(
                                        answerInterview(
                                                        url.substring("/api/interviews/sessions/".length()),
                                                        Map.of("answer", "我使用Spring Boot开发用户模块，负责接口设计与联调测试。"),
                                                        token));
                        assertThat(answered.path("sessionId").asString()).isEqualTo(sessionId);
                        assertThat(answered.path("turnCount").asInt()).isGreaterThanOrEqualTo(1);
                        assertThat(data(performGet(url, token))).isEqualTo(answered);

                        String secondId = createSession(token).path("sessionId").asString();
                        var history = data(performGet("/api/interviews/sessions", token)).path("items");
                        assertThat(history)
                                        .hasSize(2)
                                        .extracting(item -> item.path("sessionId").asString())
                                        .containsExactlyInAnyOrder(sessionId, secondId);

                        data(performDelete(url, token));
                        assertThat(performGet(url, token).getResponse().getStatus()).isEqualTo(404);
                }

                @Test
                void rejectsMissingTargetRole() throws Exception {
                        String token = account("面试用户");
                        assertThat(startInterview(Map.of("targetRole", ""), token).getResponse().getStatus())
                                        .isEqualTo(400);
                }

                @Test
                void rejectsEmptyAnswer() throws Exception {
                        String token = account("答题用户");
                        String sessionId = createSession(token).path("sessionId").asString();
                        assertThat(
                                        answerInterview(sessionId, Map.of("answer", ""), token)
                                                        .getResponse()
                                                        .getStatus())
                                        .isEqualTo(400);
                }

                @Test
                void rejectsUnknownSession() throws Exception {
                        String token = account("会话详情用户");
                        assertThat(
                                        performGet("/api/interviews/sessions/nonexistent", token)
                                                        .getResponse()
                                                        .getStatus())
                                        .isEqualTo(404);
                }

                private JsonNode createSession(String token) throws Exception {
                        return data(
                                        startInterview(
                                                        Map.of(
                                                                        "targetRole",
                                                                        "Java开发工程师",
                                                                        "resumeSummary",
                                                                        "3年Java开发经验，熟悉Spring Boot"),
                                                        token));
                }

                @Test
                void persistsTwelveRoundPlanAndEvaluationsWithIdempotentReplayAndOwnerIsolation()
                                throws Exception {
                        String token = account("Agent训练");
                        var session = data(
                                        startInterview(
                                                        Map.of(
                                                                        "targetRole",
                                                                        "Java 后端",
                                                                        "mode",
                                                                        "BACKEND_BASICS",
                                                                        "totalRounds",
                                                                        12,
                                                                        "jobDescription",
                                                                        "Java、MySQL，关注接口设计与验证"),
                                                        token));
                        assertThat(session.path("agent").path("plan")).hasSize(8);
                        assertThat(session.path("agent").path("executionMode").asString()).isEqualTo("mock");
                        String url = "/api/interviews/sessions/" + session.path("sessionId").asString();
                        for (int round = 1; round <= 12; round++) {
                                session = data(
                                                answerInterview(
                                                                url.substring("/api/interviews/sessions/".length()),
                                                                Map.of(
                                                                                "answer",
                                                                                "我负责接口设计",
                                                                                "requestId",
                                                                                "agent-answer-" + round,
                                                                                "expectedTurn",
                                                                                round),
                                                                token));
                                assertThat(data(performGet(url, token))).isEqualTo(session);
                        }
                        assertThat(session.path("state").asString()).isEqualTo("FINISHED");
                        assertThat(session.path("agent").path("evaluations")).hasSize(12);
                        assertThat(session.path("agent").path("digest")).hasSize(12);
                        var replay = data(
                                        answerInterview(
                                                        url.substring("/api/interviews/sessions/".length()),
                                                        Map.of(
                                                                        "answer",
                                                                        "我负责接口设计",
                                                                        "requestId",
                                                                        "agent-answer-1",
                                                                        "expectedTurn",
                                                                        1),
                                                        token));
                        assertThat(replay).isEqualTo(session);
                        assertThat(data(performGet(url, token))).isEqualTo(session);
                        String other = account("其他用户");
                        assertThat(performGet(url, other).getResponse().getStatus()).isEqualTo(404);
                        var request = Map.of("targetRole", "Java 后端", "mode", "BACKEND_BASICS", "totalRounds", 12);
                        var next = data(startInterview(request, token));
                        // 在可审阅记忆实验通过前，保持关闭无法审阅的近期会话计数。
                        assertThat(next.path("agent").path("practiceMemory")).isEmpty();
                        assertThat(data(startInterview(request, other)).path("agent").path("practiceMemory"))
                                        .isEmpty();
                        data(performDelete(url, token));
                        assertThat(data(startInterview(request, token)).path("agent").path("practiceMemory"))
                                        .isEmpty();
                }

                @Test
                void persistsTopicRetryEvidenceAndOwnerScopedLinks() throws Exception {
                        String token = account("专项复练");
                        String base = "/api/interviews/sessions";
                        var source = data(
                                        startInterview(
                                                        Map.of(
                                                                        "targetRole",
                                                                        "数据库专项",
                                                                        "trainingTopic",
                                                                        "DATABASE",
                                                                        "totalRounds",
                                                                        6,
                                                                        "difficulty",
                                                                        "ADVANCED"),
                                                        token));
                        String id = source.path("sessionId").asString();
                        for (int round = 1; round <= 6; round++)
                                source = data(
                                                answerInterview(
                                                                id,
                                                                Map.of(
                                                                                "answer",
                                                                                "我负责数据库事务验证",
                                                                                "requestId",
                                                                                "source-" + round,
                                                                                "expectedTurn",
                                                                                round),
                                                                token));
                        assertThat(source.path("state").asString()).isEqualTo("FINISHED");
                        assertThat(data(performGet(base + "/" + id, token))).isEqualTo(source);
                        var retry = data(
                                        startInterview(
                                                        Map.of(
                                                                        "targetRole",
                                                                        "客户端标题不可信",
                                                                        "retrySessionId",
                                                                        id,
                                                                        "retryRound",
                                                                        6,
                                                                        "totalRounds",
                                                                        3,
                                                                        "difficulty",
                                                                        "BEGINNER"),
                                                        token));
                        String retryId = retry.path("sessionId").asString();
                        assertThat(retry.path("targetRole").asString()).isEqualTo("数据库专项");
                        assertThat(retry.path("difficulty").asString()).isEqualTo("ADVANCED");
                        var snapshot = retry.path("agent").path("training").path("retry");
                        assertThat(snapshot.path("answer").asString()).isEqualTo("我负责数据库事务验证");
                        assertThat(snapshot.path("sourceRound").asInt()).isEqualTo(6);
                        assertThat(snapshot.path("competencyId").asString()).isEqualTo("storage");
                        assertThat(data(performGet(base + "/" + retryId, token))).isEqualTo(retry);
                        var links = data(performGet(base + "/" + id + "/retries", token));
                        assertThat(links.path("total").asInt()).isEqualTo(1);
                        assertThat(links.path("items").get(0).path("sessionId").asString()).isEqualTo(retryId);
                        for (int round = 1; round <= 3; round++)
                                retry = data(
                                                answerInterview(
                                                                retryId,
                                                                Map.of(
                                                                                "answer",
                                                                                "我负责索引和事务验证，使用慢查询日志与执行计划进行前后对照。",
                                                                                "requestId",
                                                                                "training-retry-" + round,
                                                                                "expectedTurn",
                                                                                round),
                                                                token));
                        assertThat(retry.path("agent").path("training").path("retry")).isEqualTo(snapshot);
                        assertThat(data(performGet(base + "/" + retryId, token))).isEqualTo(retry);
                        String other = account("其他用户");
                        assertThat(performGet(base + "/" + id + "/retries", other).getResponse().getStatus())
                                        .isEqualTo(404);
                        assertThat(
                                        startInterview(
                                                        Map.of(
                                                                        "targetRole",
                                                                        "复练",
                                                                        "retrySessionId",
                                                                        id,
                                                                        "retryRound",
                                                                        6,
                                                                        "totalRounds",
                                                                        3),
                                                        other)
                                                        .getResponse()
                                                        .getStatus())
                                        .isEqualTo(404);
                        assertThat(
                                        startInterview(
                                                        Map.of(
                                                                        "targetRole",
                                                                        "复练",
                                                                        "retrySessionId",
                                                                        id,
                                                                        "retryRound",
                                                                        12,
                                                                        "totalRounds",
                                                                        3),
                                                        token)
                                                        .getResponse()
                                                        .getStatus())
                                        .isEqualTo(400);
                        assertThat(
                                        startInterview(
                                                        Map.of(
                                                                        "targetRole",
                                                                        "专项",
                                                                        "trainingTopic",
                                                                        "DATABASE",
                                                                        "totalRounds",
                                                                        7),
                                                        token)
                                                        .getResponse()
                                                        .getStatus())
                                        .isEqualTo(400);
                        data(performDelete(base + "/" + id, token));
                        assertThat(
                                        data(performGet(base + "/" + retryId, token))
                                                        .path("agent")
                                                        .path("training")
                                                        .path("retry"))
                                        .isEqualTo(snapshot);
                }

                @Test
                void customJdAndDifficultySurviveAnswersReloadAndSourceChanges() throws Exception {
                        String token = account("自定义面试");
                        String jd = "Java、MySQL；负责订单一致性与接口设计。".repeat(300);
                        var request = objectMapper
                                        .createObjectNode()
                                        .put("targetRole", "后端工程师")
                                        .put("jobDescription", jd)
                                        .put("difficulty", "ADVANCED")
                                        .put("totalRounds", 2);
                        var session = data(startInterview(request, token));
                        String url = "/api/interviews/sessions/" + session.path("sessionId").asString();
                        assertThat(session.path("difficulty").asString()).isEqualTo("ADVANCED");
                        assertThat(session.path("jobSnapshot").asString()).contains(jd);
                        request.put("jobDescription", "客户端后来修改的 JD");
                        for (int turn = 1; turn <= 2; turn++) {
                                var answered = data(
                                                answerInterview(
                                                                url.substring("/api/interviews/sessions/".length()),
                                                                Map.of("answer", "我负责接口设计与对照测试", "expectedTurn", turn),
                                                                token));
                                assertThat(answered.path("difficulty").asString()).isEqualTo("ADVANCED");
                        }
                        var stored = data(performGet(url, token));
                        assertThat(stored.path("jobSnapshot").asString())
                                        .contains(jd)
                                        .doesNotContain("客户端后来修改");
                        assertThat(stored.path("difficulty").asString()).isEqualTo("ADVANCED");
                        assertThat(stored.path("state").asString()).isEqualTo("FINISHED");
                        request.remove("difficulty");
                        assertThat(data(startInterview(request, token)).path("difficulty").asString())
                                        .isEqualTo("STANDARD");
                        request.put("difficulty", "INVALID");
                        assertThat(startInterview(request, token).getResponse().getStatus()).isEqualTo(400);
                        request.put("difficulty", "BEGINNER").put("jobDescription", "原".repeat(20001));
                        assertThat(startInterview(request, token).getResponse().getStatus()).isEqualTo(400);
                }

                private ObjectNode jobRequest() {
                        var body = objectMapper
                                        .createObjectNode()
                                        .put("revision", 0)
                                        .put("title", "Java 后端实习")
                                        .put("company", "合成公司")
                                        .put(
                                                        "jdText",
                                                        "负责 Java 接口开发、MySQL 查询与 Redis 缓存验证。" + "完整岗位职责。".repeat(800));
                        body.putArray("requirements");
                        return body;
                }

                @Test
                void importsResumeAndPrivateJobThenInterviewsUsingImmutableOwnedSnapshots()
                                throws Exception {
                        String token = account("面试材料用户");
                        byte[] bytes;
                        try (var document = new XWPFDocument();
                                        var output = new ByteArrayOutputStream()) {
                                for (String line : new String[] { "姓名：候选人", "求职意向：Java 后端", "项目经历：我负责订单接口设计与对照测试" })
                                        document.createParagraph().createRun().setText(line);
                                document.write(output);
                                bytes = output.toByteArray();
                        }
                        var parsed = data(
                                        mockMvc.perform(
                                                        multipart("/api/resumes/import/parse")
                                                                        .file(
                                                                                        new MockMultipartFile(
                                                                                                        "file",
                                                                                                        "resume.docx",
                                                                                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                                                                                        bytes))
                                                                        .header("Authorization", "Bearer " + token))
                                                        .andReturn());
                        assertThat(data(performGet("/api/resumes", token))).isEmpty();
                        var corrected = (ObjectNode) parsed.path("draft").deepCopy();
                        corrected.put("ownerName", "核对后的候选人");
                        var project = corrected
                                        .putObject("details")
                                        .putArray("projects")
                                        .addObject()
                                        .put("name", "订单履约服务")
                                        .put("role", "接口负责人")
                                        .put("background", "订单交付");
                        project.putArray("contributions").add("我负责订单接口设计与对照测试");
                        project.putArray("techStack").add("Java").add("MySQL");
                        var resume = data(performPost("/api/resumes", corrected, token));
                        var job = data(performPost("/api/target-jobs", jobRequest(), token));
                        var request = objectMapper
                                        .createObjectNode()
                                        .put("targetRole", "客户端不能覆盖岗位名称")
                                        .put("resumeId", resume.path("id").asLong())
                                        .put("targetJobId", job.path("id").asString())
                                        .put("targetJobRevision", 0)
                                        .put("mode", "GENERAL")
                                        .put("totalRounds", 6);
                        var session = data(startInterview(request, token));
                        assertThat(session.path("targetRole").asString())
                                        .isEqualTo(job.path("title").asString());
                        assertThat(session.path("jobSnapshot").asString())
                                        .contains(job.path("jdText").asString(), job.path("id").asString(), "修订 0");
                        assertThat(session.path("resumeSnapshot").asString())
                                        .contains("核对后的候选人", "我负责订单接口设计与对照测试");
                        assertThat(session.path("agent").path("plan")).hasSize(5);
                        assertThat(session.path("agent").path("plan").toString())
                                        .contains("订单履约服务", "接口负责人", "订单接口设计与对照测试", "materialAnchors");
                        String url = "/api/interviews/sessions/" + session.path("sessionId").asString();
                        var changed = jobRequest().put("title", "更新后的岗位");
                        changed.put("jdText", "已修改的 JD");
                        data(performPut("/api/target-jobs/" + job.path("id").asString(), changed, token));
                        assertThat(startInterview(request, token).getResponse().getStatus()).isEqualTo(409);
                        data(
                                        performDelete(
                                                        "/api/target-jobs/" + job.path("id").asString() + "?revision=1",
                                                        token));
                        for (int round = 1; round <= 6; round++) {
                                session = data(
                                                answerInterview(
                                                                url.substring("/api/interviews/sessions/".length()),
                                                                Map.of(
                                                                                "answer",
                                                                                "我负责接口开发与真实对照验证",
                                                                                "requestId",
                                                                                "materials-answer-" + round,
                                                                                "expectedTurn",
                                                                                round),
                                                                token));
                        }
                        assertThat(session.path("state").asString()).isEqualTo("FINISHED");
                        assertThat(session.path("agent").path("evaluations")).hasSize(6);
                        assertThat(data(performGet(url, token)).path("jobSnapshot").asString())
                                        .contains(job.path("jdText").asString());
                }

                @Test
                void rejectsForeignOrMixedJobSourcesBeforeCreatingSession() throws Exception {
                        String owner = account("材料归属用户");
                        String other = account("其他材料用户");
                        var job = data(performPost("/api/target-jobs", jobRequest(), owner));
                        var request = objectMapper
                                        .createObjectNode()
                                        .put("targetRole", "Java")
                                        .put("targetJobId", job.path("id").asString())
                                        .put("targetJobRevision", 0);
                        assertThat(startInterview(request, other).getResponse().getStatus()).isEqualTo(404);
                        request.put("jobDescription", "另一份 JD");
                        assertThat(startInterview(request, owner).getResponse().getStatus()).isEqualTo(400);
                        request.remove("jobDescription");
                        request.remove("targetJobRevision");
                        assertThat(startInterview(request, owner).getResponse().getStatus()).isEqualTo(400);
                        request.put("targetJobRevision", -1);
                        assertThat(startInterview(request, owner).getResponse().getStatus()).isEqualTo(400);
                        assertThat(data(performGet("/api/interviews/sessions", owner)).path("total").asInt())
                                        .isZero();
                }

                private JsonNode data(MvcResult result) throws Exception {
                        var content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
                        assertThat(result.getResponse().getStatus()).as(content).isEqualTo(200);
                        var response = objectMapper.readTree(content);
                        assertThat(response.path("success").asBoolean()).isTrue();
                        return response.path("data");
                }
        }

        @SpringBootTest(properties = {
                        "interview.runs.worker-enabled=false",
                        "interview.runs.capacity.max-active=2",
                        "interview.runs.capacity.max-outstanding=4"
        })
        @Nested
        class Capacity extends InterviewScenario {
                @Autowired
                InterviewRunStore store;
                @Autowired
                InterviewRunModel model;
                @Autowired
                JdbcTemplate jdbc;
                @Autowired
                TransactionTemplate tx;
                @Autowired
                RunAdmission admission;
                final List<Long> owners = new ArrayList<>();

                @BeforeEach
                void setup() throws Exception {
                        for (int i = 0; i < 4; i++) {
                                String token = account("容量验证");
                                owners.add(ownerId(token));
                        }
                }

                InterviewRunStore.Row accept(int user, String key) {
                        return store.accept(
                                        owners.get(user),
                                        "START",
                                        key,
                                        key,
                                        null,
                                        0,
                                        () -> new InterviewRunStore.Fixed(
                                                        UUID.randomUUID().toString(),
                                                        "{}",
                                                        model.configuration(),
                                                        "TOOL"));
                }

                InterviewRunStore.Row fresh(InterviewRunStore.Row row) {
                        return store.get(row.owner(), row.id());
                }

                @Test
                void queueRejectionIsNotQuotaAndReplayNeverChargesTwice() {
                        var first = accept(0, "queue_first");
                        assertThatThrownBy(() -> accept(0, "queue_second"))
                                        .isInstanceOfSatisfying(
                                                        BusinessException.class,
                                                        ex -> assertThat(ex.code())
                                                                        .isEqualTo(ErrorCode.RUN_USER_CAPACITY));
                        assertThat(accept(0, "queue_first").id()).isEqualTo(first.id());
                        var claim = store.claim(first);
                        var second = accept(0, "queue_second");
                        assertThat(store.claim(second)).isNull();
                        assertThatThrownBy(() -> accept(0, "queue_third"))
                                        .isInstanceOf(BusinessException.class);
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT SUM(used_units) FROM request_quotas WHERE scope_key=?",
                                                        Integer.class,
                                                        InterviewRunStore.sha("ai-day:" + owners.getFirst())))
                                        .isEqualTo(6);
                        store.fail(claim, "TEST_DONE");
                        assertThat(store.claim(second)).isNotNull();
                }

                @Test
                void capacityIsSharedAcrossClaimersAndReleasesAfterTerminalState() {
                        var a = accept(0, "a");
                        var b = accept(1, "b");
                        var c = accept(2, "c");
                        var ca = store.claim(a);
                        assertThat(store.claim(b)).isNotNull();
                        assertThat(store.claim(c)).isNull();
                        assertThat(fresh(c).epoch()).isZero();
                        store.fail(ca, "TEST_DONE");
                        assertThat(store.claim(c)).isNotNull();
                }

                @Test
                void finalQueueSlotHasOneWinnerAndFailedAcceptanceLeavesNoOutboxOrCharge()
                                throws Exception {
                        var a = accept(0, "a");
                        var b = accept(1, "b");
                        store.claim(a);
                        store.claim(b);
                        accept(0, "waiting");
                        var start = new CountDownLatch(1);
                        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                                var results = new ArrayList<Future<Boolean>>();
                                for (int user = 2; user <= 3; user++) {
                                        int index = user;
                                        results.add(
                                                        pool.submit(
                                                                        () -> {
                                                                                start.await();
                                                                                try {
                                                                                        accept(index, "last");
                                                                                        return true;
                                                                                } catch (BusinessException ex) {
                                                                                        assertThat(ex.code())
                                                                                                        .isEqualTo(ErrorCode.RUN_QUEUE_FULL);
                                                                                        return false;
                                                                                }
                                                                        }));
                                }
                                start.countDown();
                                int wins = 0;
                                for (var result : results)
                                        if (result.get(5, TimeUnit.SECONDS))
                                                wins++;
                                assertThat(wins).isEqualTo(1);
                        }
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT COUNT(*) FROM interview_runs WHERE status IN ('ACCEPTED','RUNNING')",
                                                        Integer.class))
                                        .isEqualTo(4);
                        for (int user = 2; user <= 3; user++) {
                                if (store.command(owners.get(user), "START", "last").isEmpty())
                                        assertThat(
                                                        jdbc.queryForObject(
                                                                        "SELECT COUNT(*) FROM request_quotas WHERE scope_key=?",
                                                                        Integer.class,
                                                                        InterviewRunStore.sha(
                                                                                        "ai-day:" + owners.get(user))))
                                                        .isZero();
                        }
                }

                @Test
                void queueExpiryWorksWhileAllSlotsAreBusyAndDoesNotCallModel() {
                        var a = accept(0, "a");
                        var b = accept(1, "b");
                        var c = accept(2, "c");
                        store.claim(a);
                        store.claim(b);
                        jdbc.update(
                                        "UPDATE interview_runs SET queue_deadline=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
                                        c.id());
                        store.expireWaiting();
                        store.expireWaiting();
                        assertThat(fresh(c).status()).isEqualTo("EXPIRED");
                        assertThat(fresh(c).error()).isEqualTo("RUN_QUEUE_TIMEOUT");
                        assertThat(fresh(c).attempts()).isZero();
                        assertThat(
                                        store.events(c.owner(), c.id(), 0).stream()
                                                        .filter(e -> e.type().equals("run.expired")))
                                        .hasSize(1);
                        assertThat(fresh(a).status()).isEqualTo("RUNNING");
                        assertThat(accept(2, "new")).isNotNull();
                }

                @Test
                void competingClaimersCannotTakeTheLastExecutionSlotTwice() throws Exception {
                        store.claim(accept(0, "occupant"));
                        var b = accept(1, "b");
                        var c = accept(2, "c");
                        var start = new CountDownLatch(1);
                        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                                var first = pool.submit(
                                                () -> {
                                                        start.await();
                                                        return store.claim(b);
                                                });
                                var second = pool.submit(
                                                () -> {
                                                        start.await();
                                                        return store.claim(c);
                                                });
                                start.countDown();
                                var results = Arrays.asList(
                                                first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
                                assertThat(results.stream().filter(Objects::nonNull)).hasSize(1);
                        }
                }

                @Test
                void expiredStaleCandidateCannotAcquireLeaseEvenWithoutReaper() {
                        var row = accept(0, "stale");
                        jdbc.update(
                                        "UPDATE interview_runs SET queue_deadline=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
                                        row.id());
                        assertThat(store.claim(row)).isNull();
                        assertThat(fresh(row).status()).isEqualTo("EXPIRED");
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT first_started_at FROM interview_runs WHERE id=?",
                                                        java.sql.Timestamp.class,
                                                        row.id()))
                                        .isNull();
                }

                @Test
                void backoffDoesNotRestartQueueDeadlineOrLetWaitingCommandOvertake() {
                        var a = accept(0, "a");
                        var ca = store.claim(a);
                        var waiting = accept(0, "waiting");
                        var cp = objectMapper
                                        .createObjectNode()
                                        .put("stage", "MODEL")
                                        .put("outputPhase", "ACTION")
                                        .put("repaired", false)
                                        .put("actionRepaired", false);
                        cp.putArray("sources");
                        cp.putArray("messages")
                                        .addObject()
                                        .put("role", "system")
                                        .put("content", "ACTION fixture");
                        store.checkpoint(ca, cp.toString(), "MODEL", null, null);
                        var attempt = store.reserveModelAttempt(ca, prepared("hash")).id();
                        assertThat(store.deferModelFailure(ca, attempt, "MODEL_NETWORK", 1, cp.toString()))
                                        .isTrue();
                        jdbc.update(
                                        "UPDATE interview_runs SET queue_deadline=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)),"
                                                        + "not_before=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
                                        a.id());
                        store.expireWaiting();
                        assertThat(fresh(a).status()).isEqualTo("ACCEPTED");
                        assertThat(store.claim(waiting)).isNull();
                        assertThat(store.claim(a)).isNotNull();
                        assertThat(store.claim(accept(1, "other_user"))).isNotNull();
                }

                @Test
                void rollbackAndCancelledQueueReleaseCapacityAndLegacyRowsKeepQueueSemantics() {
                        tx.executeWithoutResult(
                                        status -> {
                                                accept(0, "rollback");
                                                status.setRollbackOnly();
                                        });
                        assertThat(store.command(owners.getFirst(), "START", "rollback")).isEmpty();
                        var a = accept(0, "a");
                        store.cancel(a.owner(), a.id());
                        assertThat(accept(0, "b")).isNotNull();
                        var old = accept(1, "old");
                        jdbc.update(
                                        "UPDATE interview_runs SET queue_deadline=NULL,created_at=TIMESTAMPADD(SECOND,-20,CURRENT_TIMESTAMP(6)) WHERE id=?",
                                        old.id());
                        assertThat(store.claim(old)).isNotNull();
                        assertThatThrownBy(admission::lock).isInstanceOf(IllegalStateException.class);
                }

                @Test
                void snapshotSourceLocksAreAcquiredBeforeTheSharedAdmissionMutex() {
                        var independent = new TransactionTemplate(tx.getTransactionManager());
                        independent.setPropagationBehavior(
                                        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                        var row = store.accept(
                                        owners.getFirst(),
                                        "START",
                                        "source_lock_order",
                                        "hash",
                                        null,
                                        0,
                                        () -> {
                                                independent.executeWithoutResult(
                                                                s -> assertThat(
                                                                                jdbc.queryForObject(
                                                                                                "SELECT id FROM agent_run_admission WHERE id=1 FOR UPDATE NOWAIT",
                                                                                                Integer.class))
                                                                                .isEqualTo(1));
                                                return new InterviewRunStore.Fixed(
                                                                UUID.randomUUID().toString(),
                                                                "{}",
                                                                model.configuration(),
                                                                "TOOL");
                                        });
                        assertThat(row.status()).isEqualTo("ACCEPTED");
                }
        }

        @Nested
        class TrainingSources extends InterviewRunScenario {
                @Autowired
                JdbcTemplate jdbc;
                @Autowired
                org.springframework.transaction.support.TransactionTemplate tx;

                @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
                cn.edu.whu.zhizhida.interview.api.QuestionCatalog questions;

                static final String TASKS = "/api/interviews/training-tasks";

                JsonNode data(org.springframework.test.web.servlet.MvcResult result) throws Exception {
                        assertThat(result.getResponse().getStatus())
                                        .as(result.getResponse().getContentAsString())
                                        .isIn(200, 202);
                        return objectMapper
                                        .readTree(
                                                        result.getResponse()
                                                                        .getContentAsString(
                                                                                        java.nio.charset.StandardCharsets.UTF_8))
                                        .path("data");
                }

                Map<String, Object> question() {
                        var q = jdbc.queryForMap(
                                        "SELECT id,version FROM question_bank WHERE published=TRUE ORDER BY id LIMIT 1");
                        return new HashMap<>(
                                        Map.of(
                                                        "requestId",
                                                        UUID.randomUUID().toString(),
                                                        "sourceType",
                                                        "QUESTION",
                                                        "questionId",
                                                        q.get("id"),
                                                        "questionVersion",
                                                        q.get("version")));
                }

                Map<String, Object> knowledge() throws Exception {
                        var doc = data(
                                        performPost(
                                                        "/api/knowledge/documents",
                                                        Map.of(
                                                                        "title",
                                                                        "幂等笔记",
                                                                        "content",
                                                                        "# 幂等\n使用请求号唯一约束，并发冲突后读取已保存结果。"),
                                                        token));
                        return new HashMap<>(
                                        Map.of(
                                                        "requestId",
                                                        UUID.randomUUID().toString(),
                                                        "sourceType",
                                                        "KNOWLEDGE",
                                                        "documentId",
                                                        doc.path("id").asString(),
                                                        "documentVersion",
                                                        1,
                                                        "trainingTopic",
                                                        "DATABASE"));
                }

                JsonNode add(Map<String, Object> input) throws Exception {
                        return data(performPost(TASKS + "/from-source", input, token));
                }

                JsonNode start(String id, String key) throws Exception {
                        return data(
                                        performPost(
                                                        TASKS + "/" + id + "/start-commands", Map.of("requestId", key),
                                                        token));
                }

                String execute(JsonNode run) {
                        String id = run.path("runId").asString();
                        run(id);
                        assertThat(store.get(owner, id).status())
                                        .as(store.get(owner, id).error())
                                        .isEqualTo("SUCCEEDED");
                        return store.get(owner, id).sessionId();
                }

                @Test
                void directQuestionPreservesVersionAndActualProgressAcrossReopen() throws Exception {
                        var input = question();
                        var task = add(input);
                        String id = task.path("id").asString();
                        assertThat(task.path("sourceType").asString()).isEqualTo("QUESTION");
                        assertThat(task.path("sourceSessionId").asString()).isEmpty();
                        assertThat(add(input).path("id")).isEqualTo(task.path("id"));
                        assertThat(
                                        performPost(TASKS + "/" + id + "/start", Map.of(), token)
                                                        .getResponse()
                                                        .getStatus())
                                        .isEqualTo(404);
                        var run = start(id, "question_start_001");
                        String session = execute(run);
                        assertThat(start(id, "question_start_002").path("runId")).isEqualTo(run.path("runId"));
                        var saved = data(performGet("/api/interviews/sessions/" + session, token));
                        assertThat(
                                        saved.path("agent")
                                                        .path("training")
                                                        .path("question")
                                                        .path("version")
                                                        .asInt())
                                        .isEqualTo(input.get("questionVersion"));
                        for (int round = 1; round <= 3; round++)
                                execute(
                                                data(
                                                                performPost(
                                                                                "/api/interviews/sessions/" + session
                                                                                                + "/answer-commands",
                                                                                Map.of(
                                                                                                "requestId",
                                                                                                "question_answer_"
                                                                                                                + round,
                                                                                                "expectedTurn",
                                                                                                round,
                                                                                                "answer",
                                                                                                "我负责写入接口，用唯一索引限制请求号。冲突读取已有结果，失败不写成功记录。"),
                                                                                token)));
                        var listed = data(performGet(TASKS, token)).path("items").get(0);
                        assertThat(listed.path("answeredCount").asInt()).isEqualTo(3);
                        assertThat(listed.path("status").asString()).isEqualTo("COMPLETED");
                }

                @Test
                void sourceCreationIsConcurrentIdempotentAndRejectsKeyReuse() throws Exception {
                        var input = question();
                        var gate = new CountDownLatch(1);
                        try (var pool = Executors.newFixedThreadPool(2)) {
                                var a = pool.submit(
                                                () -> {
                                                        gate.await();
                                                        return add(input);
                                                });
                                var b = pool.submit(
                                                () -> {
                                                        gate.await();
                                                        return add(input);
                                                });
                                gate.countDown();
                                assertThat(a.get(15, TimeUnit.SECONDS).path("id"))
                                                .isEqualTo(b.get(15, TimeUnit.SECONDS).path("id"));
                        }
                        input.put("questionVersion", 999);
                        assertThat(performPost(TASKS + "/from-source", input, token).getResponse().getStatus())
                                        .isEqualTo(409);
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT COUNT(*) FROM interview_training_tasks WHERE user_id=?",
                                                        Integer.class,
                                                        owner))
                                        .isEqualTo(1);
                }

                @org.junit.jupiter.params.ParameterizedTest
                @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
                void queuedQuestionOpeningRejectsWithdrawalBeforeAnyModelAttempt(boolean unpublish)
                                throws Exception {
                        var input = question();
                        String questionId = (String) input.get("questionId");
                        int version = ((Number) input.get("questionVersion")).intValue();
                        String id = start(add(input).path("id").asString(), "withdraw_before_worker")
                                        .path("runId")
                                        .asString();
                        try {
                                if (unpublish)
                                        jdbc.update("UPDATE question_bank SET published=FALSE WHERE id=?", questionId);
                                else
                                        jdbc.update(
                                                        "UPDATE question_bank SET version=version+1 WHERE id=?",
                                                        questionId);
                                run(id);
                                assertThat(store.get(owner, id).status()).isEqualTo("FAILED");
                                assertThat(store.get(owner, id).error()).isEqualTo("RUN_SOURCE_REVOKED");
                                assertThat(store.get(owner, id).attempts()).isZero();
                                assertThat(store.events(owner, id, 0))
                                                .noneMatch(event -> event.type().equals("turn.committed"));
                        } finally {
                                jdbc.update(
                                                "UPDATE question_bank SET version=?,published=TRUE WHERE id=?",
                                                version,
                                                questionId);
                        }
                }

                @Test
                void withdrawalAfterWorkerPrecheckRollsBackTheOpeningCommit() throws Exception {
                        var input = question();
                        String questionId = (String) input.get("questionId");
                        int version = ((Number) input.get("questionVersion")).intValue();
                        String id = start(add(input).path("id").asString(), "withdraw_before_commit")
                                        .path("runId")
                                        .asString();
                        try (var executor = Executors.newSingleThreadExecutor()) {
                                org.mockito.Mockito.doAnswer(
                                                invocation -> {
                                                        // 在 Worker 普通读取之后、最终事务加锁读取之前，独立提交题目下架，验证并发撤销。
                                                        executor.submit(
                                                                        () -> jdbc.update(
                                                                                        "UPDATE question_bank SET published=FALSE WHERE id=?",
                                                                                        questionId))
                                                                        .get(5, TimeUnit.SECONDS);
                                                        return invocation.callRealMethod();
                                                })
                                                .when(
                                                                org.springframework.test.util.AopTestUtils.<cn.edu.whu.zhizhida.interview.api.QuestionCatalog>getUltimateTargetObject(
                                                                                questions))
                                                .lockPublishedForExecution(owner, questionId, version);
                                run(id);
                                assertThat(store.get(owner, id).error()).isEqualTo("RUN_SOURCE_REVOKED");
                                assertThat(store.get(owner, id).status()).isEqualTo("FAILED");
                                assertThat(
                                                jdbc.queryForObject(
                                                                "SELECT COUNT(*) FROM interview_sessions WHERE user_id=?",
                                                                Integer.class,
                                                                owner))
                                                .isZero();
                                assertThat(store.events(owner, id, 0))
                                                .noneMatch(event -> event.type().equals("turn.committed"));
                        } finally {
                                jdbc.update("UPDATE question_bank SET published=TRUE WHERE id=?", questionId);
                        }
                }

                @Test
                void knowledgeUpdateBeforeStartRequiresReviewInsteadOfSilentReplacement() throws Exception {
                        var input = knowledge();
                        var task = add(input);
                        String doc = (String) input.get("documentId"), id = task.path("id").asString();
                        assertThat(task.path("sourceDocumentVersion").asInt()).isEqualTo(1);
                        data(
                                        performPut(
                                                        "/api/knowledge/documents/" + doc + "/content",
                                                        Map.of("expectedVersion", 1, "content", "更新：事务提交后才确认消息。"),
                                                        token));
                        assertThat(
                                        performPost(
                                                        TASKS + "/" + id + "/start-commands",
                                                        Map.of("requestId", "stale_source_start"),
                                                        token)
                                                        .getResponse()
                                                        .getStatus())
                                        .isEqualTo(409);
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT active_run_id FROM interview_training_tasks WHERE id=?",
                                                        String.class,
                                                        id))
                                        .isNull();
                        assertThat(add(input).path("id")).isEqualTo(task.path("id"));
                        input.put("requestId", UUID.randomUUID().toString());
                        assertThat(performPost(TASKS + "/from-source", input, token).getResponse().getStatus())
                                        .isEqualTo(409);
                        input.put("documentVersion", 2);
                        execute(start(add(input).path("id").asString(), "current_source_start"));
                }

                @Test
                void knowledgeAcceptedSnapshotSurvivesEditAndDeletionRevokesInflightRun() throws Exception {
                        var input = knowledge();
                        String doc = (String) input.get("documentId");
                        String id = add(input).path("id").asString();
                        var run = start(id, "knowledge_snapshot_001");
                        data(
                                        performPut(
                                                        "/api/knowledge/documents/" + doc + "/content",
                                                        Map.of("expectedVersion", 1, "content", "新版不同的正文。"),
                                                        token));
                        String session = execute(run);
                        var snapshot = data(performGet("/api/interviews/sessions/" + session, token))
                                        .path("agent")
                                        .path("training")
                                        .path("knowledge")
                                        .get(0);
                        assertThat(snapshot.path("version").asInt()).isEqualTo(1);
                        assertThat(snapshot.path("content").asString()).contains("唯一约束");
                        input.put("requestId", UUID.randomUUID().toString());
                        input.put("documentVersion", 2);
                        String next = add(input).path("id").asString();
                        var pending = start(next, "revoked_source_001");
                        data(performDelete("/api/knowledge/documents/" + doc, token));
                        String runId = pending.path("runId").asString();
                        run(runId);
                        assertThat(store.get(owner, runId).status()).isEqualTo("FAILED");
                        assertThat(
                                        data(performGet("/api/interviews/sessions/" + session, token))
                                                        .path("sourceRevoked")
                                                        .asBoolean())
                                        .isTrue();
                        assertThat(
                                        performPost(
                                                        TASKS + "/" + next + "/start-commands",
                                                        Map.of("requestId", "deleted_retry_001"),
                                                        token)
                                                        .getResponse()
                                                        .getStatus())
                                        .isEqualTo(409);
                        data(performDelete(TASKS + "/" + next, token));
                }

                @Test
                void currentReadRejectsAnEditCommittedAfterTheTransactionSnapshot() throws Exception {
                        var input = knowledge();
                        String doc = (String) input.get("documentId"), task = add(input).path("id").asString();
                        try (var pool = Executors.newSingleThreadExecutor()) {
                                Integer http = tx.execute(
                                                status -> {
                                                        assertThat(
                                                                        jdbc.queryForObject(
                                                                                        "SELECT version FROM knowledge_documents WHERE id=?",
                                                                                        Integer.class,
                                                                                        doc))
                                                                        .isEqualTo(1);
                                                        try {
                                                                pool.submit(
                                                                                () -> data(
                                                                                                performPut(
                                                                                                                "/api/knowledge/documents/"
                                                                                                                                + doc
                                                                                                                                + "/content",
                                                                                                                Map.of(
                                                                                                                                "expectedVersion",
                                                                                                                                1,
                                                                                                                                "content",
                                                                                                                                "并发编辑后的正文"),
                                                                                                                token)))
                                                                                .get(15, TimeUnit.SECONDS);
                                                                // 故意让普通读取仍返回 v1，验证授权判断必须依据加锁后的当前版本。
                                                                assertThat(
                                                                                jdbc.queryForObject(
                                                                                                "SELECT version FROM knowledge_documents WHERE id=?",
                                                                                                Integer.class,
                                                                                                doc))
                                                                                .isEqualTo(1);
                                                                int code = performPost(
                                                                                TASKS
                                                                                                + "/"
                                                                                                + task
                                                                                                + "/start-commands",
                                                                                Map.of(
                                                                                                "requestId",
                                                                                                "stale_snapshot_start"),
                                                                                token)
                                                                                .getResponse()
                                                                                .getStatus();
                                                                status.setRollbackOnly();
                                                                return code;
                                                        } catch (Exception e) {
                                                                throw new RuntimeException(e);
                                                        }
                                                });
                                assertThat(http).isEqualTo(409);
                        }
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT active_run_id FROM interview_training_tasks WHERE id=?",
                                                        String.class,
                                                        task))
                                        .isNull();
                }

                @Test
                void sourceShapesOwnershipPauseAndCapacityAreEnforced() throws Exception {
                        var input = knowledge();
                        String id = add(input).path("id").asString();
                        String other = account("其他来源用户");
                        assertThat(performPost(TASKS + "/from-source", input, other).getResponse().getStatus())
                                        .isIn(404, 409);
                        assertThat(
                                        performPost(
                                                        TASKS + "/" + id + "/start-commands",
                                                        Map.of("requestId", "foreign_start_001"),
                                                        other)
                                                        .getResponse()
                                                        .getStatus())
                                        .isEqualTo(404);
                        data(performPost(TASKS + "/" + id + "/status", Map.of("action", "PAUSE"), token));
                        assertThat(
                                        performPost(
                                                        TASKS + "/" + id + "/start-commands",
                                                        Map.of("requestId", "paused_start_001"),
                                                        token)
                                                        .getResponse()
                                                        .getStatus())
                                        .isEqualTo(400);
                        data(performPost(TASKS + "/" + id + "/status", Map.of("action", "RESUME"), token));
                        execute(start(id, "resumed_start_001"));
                        for (String field : List.of("documentVersion", "documentId", "trainingTopic")) {
                                var invalid = new HashMap<>(input);
                                invalid.remove(field);
                                assertThat(
                                                performPost(TASKS + "/from-source", invalid, token)
                                                                .getResponse()
                                                                .getStatus())
                                                .isEqualTo(400);
                        }
                        var invalid = question();
                        invalid.put("documentId", input.get("documentId"));
                        assertThat(
                                        performPost(TASKS + "/from-source", invalid, token)
                                                        .getResponse()
                                                        .getStatus())
                                        .isEqualTo(400);
                        invalid = question();
                        invalid.remove("questionVersion");
                        assertThat(
                                        performPost(TASKS + "/from-source", invalid, token)
                                                        .getResponse()
                                                        .getStatus())
                                        .isEqualTo(400);
                        input.put("questionId", "unexpected");
                        assertThat(performPost(TASKS + "/from-source", input, token).getResponse().getStatus())
                                        .isEqualTo(400);
                        for (int i = 1; i < 500; i++)
                                jdbc.update(
                                                "INSERT INTO interview_training_tasks(id,user_id,source_round,title,gap_text) VALUES(?,?,0,'synthetic','capacity')",
                                                UUID.randomUUID().toString(),
                                                owner);
                        assertThat(
                                        performPost(TASKS + "/from-source", question(), token)
                                                        .getResponse()
                                                        .getStatus())
                                        .isEqualTo(400);
                }
        }

        @Nested
        class CommandIdempotency extends InterviewRunScenario {
                @Autowired
                JdbcTemplate jdbc;

                String opened() throws Exception {
                        String id = acceptStart(Map.of("targetRole", "Java 后端", "totalRounds", 3));
                        runSuccessfully(id);
                        return store.get(owner, id).sessionId();
                }

                @Test
                void repeatedCommandsShareOneRunAndChargeOnce() throws Exception {
                        String session = opened();
                        String id = acceptAnswer(session, "answer-replay-001", 1, "我使用 Redis 缓存请求号。");
                        runSuccessfully(id);
                        assertThat(acceptAnswer(session, "answer-replay-001", 1, "我使用 Redis 缓存请求号。"))
                                        .isEqualTo(id);
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT COUNT(*) FROM interview_runs WHERE owner_id=? AND operation='ANSWER'",
                                                        Integer.class,
                                                        owner))
                                        .isEqualTo(1);
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT turn_count FROM interview_sessions WHERE user_id=? AND session_id=?",
                                                        Integer.class,
                                                        owner,
                                                        session))
                                        .isEqualTo(2);
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT SUM(used_units) FROM request_quotas WHERE scope_key=?",
                                                        Integer.class,
                                                        InterviewRunStore.sha("ai-day:" + owner)))
                                        .isEqualTo(5);
                }

                @Test
                void concurrentRequestsShareOneRunAndCannotChangeTheInput() throws Exception {
                        String session = opened();
                        String id;
                        try (var pool = Executors.newFixedThreadPool(2)) {
                                var first = pool.submit(
                                                () -> acceptAnswer(
                                                                session, "answer-race-001", 1, "我用 Redis 缓存请求号。"));
                                var second = pool.submit(
                                                () -> acceptAnswer(
                                                                session, "answer-race-001", 1, "我用 Redis 缓存请求号。"));
                                id = first.get(15, TimeUnit.SECONDS);
                                assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(id);
                        }
                        runSuccessfully(id);
                        var conflict = performPost(
                                        "/api/interviews/sessions/" + session + "/answer-commands",
                                        Map.of(
                                                        "answer",
                                                        "替换后的回答",
                                                        "requestId",
                                                        "answer-race-001",
                                                        "expectedTurn",
                                                        1),
                                        token);
                        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
                }

                @Test
                void trainingCommandUsesAtomicTaskBindingAndHonorsPause() throws Exception {
                        var question = jdbc.queryForMap(
                                        "SELECT id,version FROM question_bank WHERE published=TRUE ORDER BY id LIMIT 1");
                        var created = performPost(
                                        "/api/interviews/training-tasks/from-source",
                                        Map.of(
                                                        "requestId",
                                                        "task-create-001",
                                                        "sourceType",
                                                        "QUESTION",
                                                        "questionId",
                                                        question.get("id"),
                                                        "questionVersion",
                                                        question.get("version")),
                                        token);
                        assertThat(created.getResponse().getStatus()).isEqualTo(200);
                        String task = (String) dataMap(created).get("id");
                        String path = "/api/interviews/training-tasks/" + task;
                        var result = performPost(
                                        path + "/start-commands", Map.of("requestId", "task-start-001"), token);
                        assertThat(result.getResponse().getStatus()).isEqualTo(202);
                        String run = (String) dataMap(result).get("runId");
                        runSuccessfully(run);
                        String session = store.get(owner, run).sessionId();
                        var replay = performPost(
                                        path + "/start-commands", Map.of("requestId", "task-start-002"), token);
                        assertThat(replay.getResponse().getStatus()).isEqualTo(202);
                        assertThat(dataMap(replay).get("sessionId")).isEqualTo(session);
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT COUNT(*) FROM interview_runs WHERE owner_id=? AND training_task_id=?",
                                                        Integer.class,
                                                        owner,
                                                        task))
                                        .isEqualTo(1);
                        assertThat(
                                        jdbc.queryForObject(
                                                        "SELECT active_session_id FROM interview_training_tasks WHERE user_id=? AND id=?",
                                                        String.class,
                                                        owner,
                                                        task))
                                        .isEqualTo(session);
                        assertThat(
                                        performPost(path + "/status", Map.of("action", "PAUSE"), token)
                                                        .getResponse()
                                                        .getStatus())
                                        .isEqualTo(200);
                        assertThat(
                                        performPost(
                                                        path + "/start-commands",
                                                        Map.of("requestId", "task-start-003"),
                                                        token)
                                                        .getResponse()
                                                        .getStatus())
                                        .isEqualTo(400);
                }
        }
}
