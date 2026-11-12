package cn.edu.whu.zhizhida.learning;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import cn.edu.whu.zhizhida.interview.GoalTrainingAdapter;
import cn.edu.whu.zhizhida.interview.execution.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.springframework.test.context.TestPropertySource(properties={
        "interview.runs.loop-policy=A1", "interview.runs.decision-protocol=phased-v2", "interview.agent.adaptive-routing-enabled=true"})
class LearningGoalsIT extends IntegrationTestBase {
    @Autowired JdbcTemplate jdbc;
    @Autowired InterviewRunStore store;
    @Autowired InterviewRunWorker worker;
    @Autowired LearningGoalService goals;
    @MockitoSpyBean GoalTrainingAdapter training;
    @MockitoSpyBean cn.edu.whu.zhizhida.interview.InterviewPracticeEvidenceSource sources;
    @Autowired LearningMemoryStore observations;
    @Autowired LearningMemoryService memory;
    @Autowired GoalUpdateWorker goalUpdates;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    String token; long owner; List<String> questions;
    @BeforeEach void setup() throws Exception {
        token=registerUniqueUserToken("password123","目标验收");
        owner=data(performGet("/api/users/me",token)).path("id").asLong();
        questions=jdbc.queryForList("SELECT id FROM question_bank WHERE competency_id='storage' AND published=TRUE ORDER BY id LIMIT 3",String.class);
    }
    @AfterEach void cleanup() throws Exception {
        reset(training,sources);
        performDeleteWithBody("/api/users/me",Map.of("currentPassword","password123"),token);
    }
    JsonNode data(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        if(result.getResolvedException()!=null) throw result.getResolvedException();
        assertThat(result.getResponse().getStatus()).withFailMessage(result.getResponse().getContentAsString()).isIn(200,202);
        return objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
    }
    Map<String,Object> input(int runs) { return Map.of("requestId",UUID.randomUUID().toString(),"title","补齐存储能力", "questionIds",questions,
            "requiredScenarios",2,"maxRuns",runs,"maxTraining",3,"maxModelAttempts",120,"maxTools",90); }
    String goal(int runs) throws Exception { return data(performPost("/api/learning/goals",input(runs),token)).path("id").asText(); }
    void execute(String id) {
        var claim=store.claim(store.get(owner,id));
        worker.execute(claim);
        if(store.get(owner,id).subjectType().equals("GOAL")) assertThat(store.get(owner,id).status()).as(store.get(owner,id).error()).isEqualTo("SUCCEEDED");
    }
    JsonNode get(String goal) throws Exception { return data(performGet("/api/learning/goals/"+goal,token)); }
    String propose(String goal,String key,long revision) throws Exception {
        return data(performPost("/api/learning/goals/"+goal+"/plan-commands",Map.of("requestId",key,"expectedRevision",revision),token)).path("id").asText();
    }
    JsonNode plan(String goal) throws Exception { execute(propose(goal,UUID.randomUUID().toString(),1));return get(goal).path("plans").get(0); }
    org.springframework.test.web.servlet.MvcResult confirm(JsonNode plan,String key,int revision) throws Exception {
        return performPost("/api/learning/plans/"+plan.path("id").asText()+"/confirm",Map.of("requestId",key,"expectedRevision",revision),token);
    }
    @Test void goalRunsHaveNoFakeSessionAndResumeFencesOldEpoch() throws Exception {
        var input=input(10);String goal=data(performPost("/api/learning/goals",input,token)).path("id").asText();
        assertThat(data(performPost("/api/learning/goals",input,token)).path("id").asText()).isEqualTo(goal);
        var changed=new HashMap<>(input);changed.put("title","另一目标");
        assertThat(performPost("/api/learning/goals",changed,token).getResponse().getStatus()).isEqualTo(409);
        String run=propose(goal,"goal_recovery_001",1);
        assertThat(propose(goal,"goal_recovery_001",1)).isEqualTo(run);
        assertThat(store.get(owner,run).sessionId()).isNull();
        assertThat(store.get(owner,run).subjectId()).isEqualTo(goal);
        var first=store.claim(store.get(owner,run));
        jdbc.update("UPDATE interview_runs SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",run);
        var second=store.claim(store.get(owner,run));
        worker.execute(first);assertThat(get(goal).path("plans")).isEmpty();
        worker.execute(second);assertThat(store.get(owner,run).status()).isEqualTo("SUCCEEDED");
        assertThat(get(goal).path("reservedRuns").asInt()).isEqualTo(1);
        assertThat(get(goal).path("plans").get(0).path("limitation").asText()).contains("未进行模型语义规划");
        assertThat(store.events(owner,run,0)).extracting(e->e.type()).contains("subject.committed").doesNotContain("turn.committed");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interview_sessions WHERE user_id=?",Integer.class,owner)).isZero();
    }
    @Test void editingRequiresFreshConfirmationAndDifferentKeysApplyOnlyOnce() throws Exception {
        String goal=goal(12);var p=plan(goal);
        var item=p.path("items").get(0).deepCopy();((tools.jackson.databind.node.ObjectNode)item).put("acceptance","解释请求幂等与失败恢复");
        var edited=data(performPost("/api/learning/plans/"+p.path("id").asText()+"/revisions",Map.of("expectedRevision",1,"items",List.of(item)),token));
        assertThat(edited.path("revision").asInt()).isEqualTo(2);
        assertThat(confirm(p,"stale_confirm_001",1).getResponse().getStatus()).isEqualTo(409);
        var applied=data(confirm(edited,"fresh_confirm_001",2));
        assertThat(data(confirm(edited,"fresh_confirm_002",2)).path("taskIds")).isEqualTo(applied.path("taskIds"));
        assertThat(applied.path("taskIds")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interview_training_tasks WHERE user_id=?",Integer.class,owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM learning_plan_revisions WHERE plan_id=? AND revision=1",String.class,p.path("id").asText())).isEqualTo("SUPERSEDED");
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/goal-plan-fixture.json"),get(goal).toPrettyString());
    }
    @Test void parallelConfirmationsWithDistinctKeysHaveOneSetOfTasks() throws Exception {
        String goal=goal(12);var p=plan(goal);var latch=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{ latch.await();return data(confirm(p,"parallel_confirm_a",1)); });
            var b=pool.submit(()->{ latch.await();return data(confirm(p,"parallel_confirm_b",1)); });
            latch.countDown();assertThat(a.get(15,TimeUnit.SECONDS).path("taskIds")).isEqualTo(b.get(15,TimeUnit.SECONDS).path("taskIds"));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learning_plan_applications WHERE owner_id=?",Integer.class,owner)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interview_training_tasks WHERE user_id=?",Integer.class,owner)).isEqualTo(3);
    }
    @Test void failedApplicationRollsBackTasksReceiptsAndConsentCommand() throws Exception {
        String goal=goal(12);var p=plan(goal);var count=new AtomicInteger();
        doAnswer(call->{ if(count.incrementAndGet()==2) throw new IllegalStateException("simulated task failure");return call.callRealMethod(); })
                .when(training).apply(anyLong(),anyString(),anyString(),anyInt(),any());
        var failed = confirm(p,"rollback_confirm_001",1).getResponse();
        assertThat(failed.getStatus()).isEqualTo(500);
        assertThat(failed.getContentAsString()).doesNotContain("simulated task failure");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interview_training_tasks WHERE user_id=?",Integer.class,owner)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learning_plan_applications WHERE owner_id=?",Integer.class,owner)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learning_plan_commands WHERE owner_id=?",Integer.class,owner)).isZero();
        reset(training);assertThat(data(confirm(p,"rollback_confirm_001",1)).path("status").asText()).isEqualTo("APPLIED");
    }
    @Test void rejectedExpiredSupersededAndWrongOwnerPlansCannotApply() throws Exception {
        String goal=goal(12);var p=plan(goal);
        data(performPost("/api/learning/plans/"+p.path("id").asText()+"/reject",Map.of("expectedRevision",1),token));
        assertThat(confirm(p,"rejected_confirm_001",1).getResponse().getStatus()).isEqualTo(409);
        var expired=plan(goal);jdbc.update("UPDATE learning_plans SET expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",expired.path("id").asText());
        assertThat(confirm(expired,"expired_confirm_001",1).getResponse().getStatus()).isEqualTo(409);
        var old=plan(goal);var fresh=plan(goal);
        assertThat(confirm(old,"replaced_confirm_001",1).getResponse().getStatus()).isEqualTo(409);
        String other=registerUniqueUserToken("password123","其他账号");
        try { assertThat(performPost("/api/learning/plans/"+fresh.path("id").asText()+"/confirm",Map.of("requestId","foreign_confirm_001","expectedRevision",1),other).getResponse().getStatus()).isEqualTo(404); }
        finally { performDeleteWithBody("/api/users/me",Map.of("currentPassword","password123"),other); }
        data(confirm(fresh,"unique_request_001",1));
        var next=plan(goal);assertThat(confirm(next,"unique_request_001",1).getResponse().getStatus()).isEqualTo(409);
    }
    @Test void goalBudgetSurvivesPauseDeletionAndFailedRuns() throws Exception {
        String goal=goal(2);var p=plan(goal);var applied=data(confirm(p,"budget_confirm_001",1));
        String task=applied.path("taskIds").get(0).asText();
        var start=data(performPost("/api/interviews/training-tasks/"+task+"/start-commands",Map.of("requestId","budget_start_001"),token));
        execute(start.path("runId").asText());
        String session=store.get(owner,start.path("runId").asText()).sessionId();
        data(performDelete("/api/interviews/training-tasks/"+task,token));
        assertThat(performPost("/api/interviews/sessions/"+session+"/answer-commands",Map.of("requestId","answer_budget_001","expectedTurn",1,"answer","测试回答"),token).getResponse().getStatus()).isEqualTo(409);
        data(performPost("/api/learning/goals/"+goal+"/status",Map.of("expectedRevision",1,"action","PAUSE"),token));
        assertThat(performPost("/api/learning/goals/"+goal+"/plan-commands",Map.of("requestId","paused_plan_001","expectedRevision",2),token).getResponse().getStatus()).isEqualTo(409);
        data(performPost("/api/learning/goals/"+goal+"/status",Map.of("expectedRevision",2,"action","RESUME"),token));
        assertThat(get(goal).path("reservedRuns").asInt()).isEqualTo(2);
        assertThat(performPost("/api/learning/goals/"+goal+"/plan-commands",Map.of("requestId","exhausted_plan_001","expectedRevision",3),token).getResponse().getStatus()).isEqualTo(409);
        assertThat(data(performPost("/api/learning/goals/"+goal+"/review",Map.of(),token)).path("outcome").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
    }
    @Test void pausedGoalFencesAlreadyAcceptedPlanningAndCancellationDoesNotResetDebit() throws Exception {
        String goal=goal(10);String run=propose(goal,"pause_inflight_001",1);
        data(performPost("/api/learning/goals/"+goal+"/status",Map.of("expectedRevision",1,"action","PAUSE"),token));
        worker.execute(store.claim(store.get(owner,run)));assertThat(store.get(owner,run).status()).isEqualTo("FAILED");
        assertThat(get(goal).path("plans")).isEmpty();
        data(performPost("/api/learning/goals/"+goal+"/status",Map.of("expectedRevision",2,"action","RESUME"),token));
        assertThat(propose(goal,"pause_inflight_001",1)).isEqualTo(run);
        String cancelled=propose(goal,"cancelled_plan_001",3);store.cancel(owner,cancelled);
        assertThat(get(goal).path("reservedRuns").asInt()).isEqualTo(2);
        data(performPost("/api/learning/goals/"+goal+"/status",Map.of("expectedRevision",3,"action","CANCEL"),token));
        assertThat(performPost("/api/learning/goals/"+goal+"/status",Map.of("expectedRevision",4,"action","RESUME"),token).getResponse().getStatus()).isEqualTo(409);
    }
    @Test void targetAndPlanCannotExpandTheirQuestionScopeOrForgeRevision() throws Exception {
        var duplicate=new HashMap<>(input(10));duplicate.put("questionIds",List.of(questions.getFirst(),questions.getFirst()));
        assertThat(performPost("/api/learning/goals",duplicate,token).getResponse().getStatus()).isEqualTo(400);
        String goal=goal(10);var p=plan(goal);
        assertThat(performPost("/api/learning/goals/"+goal+"/plan-commands",Map.of("requestId","wrong_revision_001","expectedRevision",8),token).getResponse().getStatus()).isEqualTo(409);
        var forged=p.path("items").get(0).deepCopy();((tools.jackson.databind.node.ObjectNode)forged).put("questionId","not-allowed");
        assertThat(performPost("/api/learning/plans/"+p.path("id").asText()+"/revisions",Map.of("expectedRevision",1,"items",List.of(forged)),token).getResponse().getStatus()).isEqualTo(400);
        assertThat(performPost("/api/learning/plans/"+p.path("id").asText()+"/revisions",Map.of("expectedRevision",1,"items",List.of(p.path("items").get(0),p.path("items").get(0))),token).getResponse().getStatus()).isEqualTo(400);
        assertThat(data(performGet("/api/learning/goals",token))).hasSize(1);
    }
    @Test void parallelTrainingStartsCannotBothSpendTheLastRun() throws Exception {
        String goal=goal(2);var p=plan(goal);var tasks=data(confirm(p,"parallel_budget_001",1)).path("taskIds");
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{ gate.await();return performPost("/api/interviews/training-tasks/"+tasks.get(0).asText()+"/start-commands",Map.of("requestId","parallel_budget_start_a"),token).getResponse().getStatus(); });
            var b=pool.submit(()->{ gate.await();return performPost("/api/interviews/training-tasks/"+tasks.get(1).asText()+"/start-commands",Map.of("requestId","parallel_budget_start_b"),token).getResponse().getStatus(); });
            gate.countDown();assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(202,429);
        }
        // 获胜任务开始执行后，容量允许新命令等待，但目标预算仍须拒绝额外运行。
        var first=store.command(owner,"START","task-"+InterviewRunStore.sha(tasks.get(0).asText()+"\nparallel_budget_start_a"));
        var winner=first.orElseGet(()->store.command(owner,"START","task-"+InterviewRunStore.sha(tasks.get(1).asText()+"\nparallel_budget_start_b")).orElseThrow());
        assertThat(store.claim(winner)).isNotNull();
        int loser=first.isPresent()?1:0;
        assertThat(performPost("/api/interviews/training-tasks/"+tasks.get(loser).asText()+"/start-commands",
                Map.of("requestId",loser==1?"parallel_budget_start_b":"parallel_budget_start_a"),token)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(get(goal).path("reservedRuns").asInt()).isEqualTo(2);
        assertThat(get(goal).path("reservedTraining").asInt()).isEqualTo(1);
    }
    @Test void actualAnswersContinueInGoalBudgetAndMockCannotClaimImprovement() throws Exception {
        String goal=goal(10);var p=plan(goal);var tasks=data(confirm(p,"real_answers_confirm_001",1)).path("taskIds");
        String run=data(performPost("/api/interviews/training-tasks/"+tasks.get(0).asText()+"/start-commands",Map.of("requestId","real_answers_start_001"),token)).path("runId").asText();
        execute(run);String session=store.get(owner,run).sessionId();
        for(int turn=1;turn<=3;turn++) {
            String answer=data(performPost("/api/interviews/sessions/"+session+"/answer-commands",Map.of("requestId","real_goal_answer_"+turn,"expectedTurn",turn,"answer","我在事务中记录请求号并建立唯一索引。并发冲突时读取已保存结果；失败后重试仍校验原输入。"),token)).path("runId").asText();
            execute(answer);assertThat(store.get(owner,answer).status()).isEqualTo("SUCCEEDED");
        }
        var review=data(performPost("/api/learning/goals/"+goal+"/review",Map.of(),token));
        assertThat(review.path("results").get(0).path("completed").asBoolean()).isTrue();
        assertThat(review.path("validScenarios").asInt()).isZero();
        assertThat(review.path("outcome").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(get(goal).path("reservedRuns").asInt()).isEqualTo(5);
        assertThat(get(goal).path("reservedModelAttempts").asInt()).isEqualTo(3+4*store.executionBudget(owner,run).maxModelAttempts());
        assertThat(get(goal).path("status").asText()).isEqualTo("ACTIVE");
        data(performDelete("/api/interviews/sessions/"+session,token));
        assertThat(data(performPost("/api/learning/goals/"+goal+"/review",Map.of(),token)).path("results")).isEmpty();
        assertThat(get(goal).path("reservedRuns").asInt()).isEqualTo(5);
    }
    @Test void disablingNewGoalCommandsPreservesAppliedReceiptsAndReadAccess() throws Exception {
        String goal=goal(10);var p=plan(goal);var applied=data(confirm(p,"disable_replay_001",1));
        org.springframework.test.util.ReflectionTestUtils.setField(goals,"enabled",false);
        try {
            assertThat(performPost("/api/learning/goals",input(10),token).getResponse().getStatus()).isEqualTo(503);
            assertThat(performPost("/api/learning/goals/"+goal+"/plan-commands",Map.of("requestId","disabled_plan_001","expectedRevision",1),token).getResponse().getStatus()).isEqualTo(503);
            assertThat(data(confirm(p,"disable_replay_002",1)).path("taskIds")).isEqualTo(applied.path("taskIds"));
            String task=applied.path("taskIds").get(0).asText();
            assertThat(performPost("/api/interviews/training-tasks/"+task+"/start-commands",Map.of("requestId","disabled_start_001"),token).getResponse().getStatus()).isEqualTo(503);
            assertThat(get(goal).path("reservedRuns").asInt()).isEqualTo(1);
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(goals,"enabled",true); }
    }
    @Test void confirmedCriteriaInvalidateDraftsFenceWorkersAndRetainBudget() throws Exception {
        String goal=goal(12);var old=plan(goal);String run=propose(goal,"criteria_inflight_001",1);
        var criteria=Map.of("expectedRevision",1,"requiredScenarios",3,"dimensions",List.of("RESPONSIBILITY","TRADEOFFS"),"confirmed",true);
        var changed=data(performPost("/api/learning/goals/"+goal+"/criteria",criteria,token));
        assertThat(changed.path("criteriaRevision").asInt()).isEqualTo(2);
        assertThat(changed.path("reservedRuns").asInt()).isEqualTo(2);
        assertThat(confirm(old,"criteria_old_plan_001",1).getResponse().getStatus()).isEqualTo(409);
        worker.execute(store.claim(store.get(owner,run)));assertThat(store.get(owner,run).status()).isEqualTo("FAILED");
        assertThat(performPost("/api/learning/goals/"+goal+"/criteria",criteria,token).getResponse().getStatus()).isEqualTo(409);
        execute(propose(goal,"criteria_new_plan_001",2));var fresh=get(goal).path("plans").get(0);data(confirm(fresh,"criteria_apply_001",1));
        var after=new HashMap<String,Object>(criteria);after.put("expectedRevision",2);
        assertThat(performPost("/api/learning/goals/"+goal+"/criteria",after,token).getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learning_goal_criteria WHERE goal_id=?",Integer.class,goal)).isEqualTo(2);
    }
    List<cn.edu.whu.zhizhida.learning.api.GoalTraining.Result> supported(String version) {
        return java.util.stream.IntStream.range(0,2).mapToObj(i->new cn.edu.whu.zhizhida.learning.api.GoalTraining.Result(
                "task-"+i,"session-"+i,questions.get(i),true,true,"synthetic structural evidence",version,List.of("CAUSAL_EXPLANATION","RESPONSIBILITY"))).toList();
    }
    JsonNode reviewGoal(String id) throws Exception { return data(performPost("/api/learning/goals/"+id+"/review",Map.of(),token)); }
    Map<String,Object> completion(JsonNode review,String key) {
        return Map.of("requestId",key,"expectedRevision",review.path("goalRevision").asLong(),"evidenceHash",review.path("evidenceHash").asText(),
                "confirmed",true,"reviewNote","逐场核对因果解释和职责；仅确认训练目标完成，不作为专业能力认证。");
    }
    @Test void completionRequiresCurrentEvidenceAndExplicitReviewAndIsConcurrentIdempotent() throws Exception {
        String goal=goal(12);var insufficient=reviewGoal(goal);
        assertThat(performPost("/api/learning/goals/"+goal+"/complete",completion(insufficient,"insufficient_001"),token).getResponse().getStatus()).isEqualTo(409);
        doReturn(supported("version-1")).when(training).results(eq(owner),eq(goal),anyList());
        var reviewed=reviewGoal(goal);assertThat(reviewed.path("outcome").asText()).isEqualTo("AWAITING_HUMAN_REVIEW");
        var noConsent=new HashMap<>(completion(reviewed,"no_consent_001"));noConsent.put("confirmed",false);
        assertThat(performPost("/api/learning/goals/"+goal+"/complete",noConsent,token).getResponse().getStatus()).isEqualTo(400);
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{gate.await();return data(performPost("/api/learning/goals/"+goal+"/complete",completion(reviewed,"complete_parallel_a"),token));});
            var b=pool.submit(()->{gate.await();return data(performPost("/api/learning/goals/"+goal+"/complete",completion(reviewed,"complete_parallel_b"),token));});
            gate.countDown();assertThat(a.get(15,TimeUnit.SECONDS).path("status").asText()).isEqualTo("ACHIEVED");
            assertThat(b.get(15,TimeUnit.SECONDS).path("status").asText()).isEqualTo("ACHIEVED");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learning_goal_completions WHERE goal_id=?",Integer.class,goal)).isEqualTo(1);
        assertThat(get(goal).path("reservedRuns").asInt()).isZero();
        assertThat(reviewGoal(goal).path("outcome").asText()).isEqualTo("USER_CONFIRMED");
        doReturn(supported("version-2")).when(training).results(eq(owner),eq(goal),anyList());
        assertThat(performPost("/api/learning/goals/"+goal+"/complete",completion(reviewed,"complete_parallel_a"),token).getResponse().getStatus()).isEqualTo(409);
        assertThat(reviewGoal(goal).path("outcome").asText()).isEqualTo("EVIDENCE_CHANGED");
        assertThat(get(goal).path("status").asText()).isEqualTo("PAUSED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learning_goal_completions WHERE goal_id=?",Integer.class,goal)).isEqualTo(1);
    }
    @Test void staleReviewAndDuplicateScenariosCannotCompleteGoal() throws Exception {
        String goal=goal(12);
        var same=supported("v1").getFirst();doReturn(List.of(same,same)).when(training).results(eq(owner),eq(goal),anyList());
        assertThat(reviewGoal(goal).path("validScenarios").asInt()).isEqualTo(1);
        doReturn(supported("v1")).when(training).results(eq(owner),eq(goal),anyList());var reviewed=reviewGoal(goal);
        data(performPost("/api/learning/goals/"+goal+"/criteria",Map.of("expectedRevision",1,"requiredScenarios",3,
                "dimensions",List.of("CAUSAL_EXPLANATION","RESPONSIBILITY"),"confirmed",true),token));
        assertThat(performPost("/api/learning/goals/"+goal+"/complete",completion(reviewed,"stale_completion_001"),token).getResponse().getStatus()).isEqualTo(409);
        String other=registerUniqueUserToken("password123","完成越权");
        try { assertThat(performPost("/api/learning/goals/"+goal+"/complete",completion(reviewed,"foreign_complete_001"),other).getResponse().getStatus()).isEqualTo(404); }
        finally { performDeleteWithBody("/api/users/me",Map.of("currentPassword","password123"),other); }
    }
    @Test void explicitHistoryChangesDraftAndCorrectionBlocksItsApplicationAndInflightCommit() throws Exception {
        String session=data(startInterview(Map.of("targetRole","Java 后端","trainingTopic","DATABASE","totalRounds",3),token)).path("sessionId").asText();
        for(int i=1;i<=3;i++) data(answerInterview(session, Map.of("requestId",UUID.randomUUID().toString(),"expectedTurn",i,
                "answer","我负责订单写入，使用请求号唯一约束；冲突时读取已保存的结果。"),token));
        // 合成的真实模式事实仅用于验证来源事务，不用于评判专业内容或模型质量。
        tx.executeWithoutResult(status->doAnswer(inv->{
            @SuppressWarnings("unchecked") var snapshot=(Optional<cn.edu.whu.zhizhida.learning.api.PracticeEvidenceSource.Snapshot>)inv.callRealMethod();
            return snapshot.map(s->new cn.edu.whu.zhizhida.learning.api.PracticeEvidenceSource.Snapshot(s.source(),s.facts().stream().map(f->
                    new cn.edu.whu.zhizhida.learning.api.LearningMemory.Fact(f.round(),f.competencyId(),f.dimension(),f.rubricVersion(),"deepseek",
                            cn.edu.whu.zhizhida.evaluation.api.AssessmentResult.Observation.NOT_OBSERVED,f.claim(),f.evidence())).toList()));
        }).when(sources).lock(owner,session));
        for(var event:observations.pending()) if(event.owner()==owner) memory.consume(event);
        String observation=jdbc.queryForList("SELECT id FROM learning_observations WHERE owner_id=? AND competency_id='storage' ORDER BY id LIMIT 1",String.class,owner).getFirst();
        String goal=goal(12);
        var input=Map.of("requestId","history_diagnosis_001","expectedRevision",1,"observationIds",List.of(observation));
        assertThat(performPost("/api/learning/goals/"+goal+"/plan-commands",input,token).getResponse().getStatus()).isEqualTo(400);
        var prefs=data(performGet("/api/learning/observations",token)).path("preferences");
        data(performPut("/api/learning/preferences",Map.of("expectedRevision",prefs.path("revision").asLong(),"personalizationEnabled",true),token));
        String run=data(performPost("/api/learning/goals/"+goal+"/plan-commands",input,token)).path("id").asText();execute(run);
        var plan=get(goal).path("plans").get(0);assertThat(plan.path("observationRefs")).hasSize(1);
        assertThat(plan.path("items").get(0).path("acceptance").asText()).contains("演示练习");
        var second=new HashMap<String,Object>(input);second.put("requestId","history_inflight_001");
        String inflight=data(performPost("/api/learning/goals/"+goal+"/plan-commands",second,token)).path("id").asText();
        data(performPost("/api/learning/observations/"+observation+"/corrections",Map.of("expectedRevision",1,"text","原观察需要纠正"),token));
        assertThat(confirm(plan,"revoked_diagnosis_001",1).getResponse().getStatus()).isEqualTo(409);
        worker.execute(store.claim(store.get(owner,inflight)));assertThat(store.get(owner,inflight).status()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interview_training_tasks WHERE user_id=?",Integer.class,owner)).isZero();
        assertThat(get(goal).path("reservedRuns").asInt()).isEqualTo(2);
    }

    @Test void completedBatchAutomaticallyProposesOneDraftAndWaitsForConfirmation() throws Exception {
        var create=new HashMap<String,Object>(input(20));create.put("autoAdjust",true);create.put("maxTraining",6);
        String goal=data(performPost("/api/learning/goals",create,token)).path("id").asText();
        var p=plan(goal);var applied=data(confirm(p,"auto_batch_confirm",1));
        for(int i=0;i<applied.path("taskIds").size();i++) finishTask(applied.path("taskIds").get(i).asText(),"auto_batch_"+i);
        for(var event:observations.pending()) if(event.owner()==owner) memory.consume(event);
        var updates=jdbc.query("SELECT * FROM learning_goal_updates WHERE goal_id=? ORDER BY id",
                (r,n)->new GoalUpdateWorker.Update(r.getLong("id"),owner,goal,r.getString("session_id"),r.getString("source_version")),goal);
        assertThat(updates).hasSize(3);goalUpdates.process(updates.getFirst());
        var planning=get(goal);
        assertThat(planning.path("coachState").asText()).isEqualTo("PLANNING");
        assertThat(planning.path("planning").path("trigger").asText()).isEqualTo("TRAINING_COMPLETED");
        String run=planning.path("planning").path("runId").asText();execute(run);
        var finished=get(goal);
        assertThat(finished.path("coachState").asText()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(finished.path("plans")).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interview_training_tasks WHERE goal_id=?",Integer.class,goal)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learning_goal_model_attempts WHERE run_id=? AND status='SUCCEEDED'",Integer.class,run)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT tools FROM learning_goal_planning WHERE run_id=?",Integer.class,run)).isEqualTo(2);
        int runs=finished.path("reservedRuns").asInt();updates.stream().skip(1).forEach(goalUpdates::process);consumeUpdates();
        assertThat(get(goal).path("reservedRuns").asInt()).isEqualTo(runs);
        assertThat(reviewGoal(goal).path("validScenarios").asInt()).isZero();
    }

    @Test void oneCompletedTaskCanTriggerNextBatchAndChangedSourceDoesNotReplayOldEvidence() throws Exception {
        var create=new HashMap<String,Object>(input(12));create.put("autoAdjust",true);create.put("maxTraining",4);
        String goal=data(performPost("/api/learning/goals",create,token)).path("id").asText();
        var p=plan(goal);
        var edited=data(performPost("/api/learning/plans/"+p.path("id").asText()+"/revisions",Map.of("expectedRevision",1,"items",List.of(p.path("items").get(0))),token));
        var applied=data(confirm(edited,"auto_single_confirm",2));
        String session=finishTask(applied.path("taskIds").get(0).asText(),"auto_single");
        consumeUpdates();String run=get(goal).path("planning").path("runId").asText();execute(run);
        int reserved=get(goal).path("reservedRuns").asInt();
        tx.executeWithoutResult(status->assertThat(goals.replan(owner,goal,session,"withdrawn-source-version")).isTrue());
        assertThat(get(goal).path("reservedRuns").asInt()).isEqualTo(reserved);
    }

    @Test void unknownModelOutcomeCannotSpendAgainOnRecovery() throws Exception {
        String goal=goal(10);String run=propose(goal,"unknown_goal_model",1);
        var claim=store.claim(store.get(owner,run));
        jdbc.update("INSERT INTO learning_goal_planning(run_id,owner_id,epoch,configuration_hash,checkpoint_json,attempts,pending,deadline) "
                + "VALUES(?,?,?,?,'{}',1,TRUE,TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP(6)))",run,owner,claim.epoch(),"0".repeat(64));
        worker.execute(claim);
        assertThat(store.get(owner,run).status()).isEqualTo("FAILED");
        assertThat(store.get(owner,run).error()).isEqualTo("MODEL_OUTCOME_UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT attempts FROM learning_goal_planning WHERE run_id=?",Integer.class,run)).isEqualTo(1);
        assertThat(get(goal).path("plans")).isEmpty();
    }

    String finishTask(String task,String key) throws Exception {
        String run=data(performPost("/api/interviews/training-tasks/"+task+"/start-commands",Map.of("requestId",key+"_start"),token)).path("runId").asText();
        execute(run);String session=store.get(owner,run).sessionId();
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(agent_json,'$.training.objective')) FROM interview_sessions WHERE session_id=?",String.class,session))
                .isEqualTo(jdbc.queryForObject("SELECT gap_text FROM interview_training_tasks WHERE id=?",String.class,task));
        for(int turn=1;turn<=3;turn++) {
            String answer=data(performPost("/api/interviews/sessions/"+session+"/answer-commands",Map.of("requestId",key+"_answer_"+turn,"expectedTurn",turn,
                    "answer","我负责订单写入，在数据库事务中写请求号并创建唯一索引；冲突读取已有结果，提交失败则回滚并记录错误。"),token)).path("runId").asText();
            execute(answer);assertThat(store.get(owner,answer).status()).as(store.get(owner,answer).error()).isEqualTo("SUCCEEDED");
        }
        return session;
    }
    void consumeUpdates() {
        for(var event:observations.pending()) if(event.owner()==owner) memory.consume(event);
        var updates=jdbc.query("SELECT * FROM learning_goal_updates WHERE owner_id=? ORDER BY id",
                (r,n)->new GoalUpdateWorker.Update(r.getLong("id"),owner,r.getString("goal_id"),r.getString("session_id"),r.getString("source_version")),owner);
        updates.forEach(goalUpdates::process);
    }
}
