package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import cn.edu.whu.zhizhida.interview.api.InterviewState;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunStore;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunWorker;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.assertThat;

/** 显式授权的合成开场、作答与收尾，执行真实生产 Worker 和模型提示词。 */
@EnabledIfEnvironmentVariable(named="RUN_PROVIDER_LIFECYCLE",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "ai.provider=deepseek", "interview.runs.worker-enabled=false", "interview.runs.route=TOOL",
        "interview.runs.loop-policy=B0", "interview.runs.decision-protocol=phased-v2",
        "app.run-dispatch.mode=database", "app.run-recovery.enabled=false", "app.outbox.publisher.enabled=false",
        "knowledge.dispatch.mode=local", "knowledge.import.worker-enabled=false", "agent.vector.enabled=false",
        "agent.mcp.reference.enabled=false", "learning.worker-enabled=false"})
class ProviderInterviewLifecycleIT extends IntegrationTestBase {
    private static final String MATERIAL="合成订单项目使用请求号唯一约束，冲突读取原订单结果。业务与 outbox 同一事务写入，提交后投递器重发，消费端去重。Redis 成功后数据库写入失败仍需处理重试。";
    private static final String ANSWER="我负责合成订单项目的幂等接口，用请求号唯一约束防止重复写入；发生冲突后读取原订单结果。"
            +"因为订单已提交但消息发送失败会造成不一致，我在同一事务内写订单和 outbox，提交后投递器重发，消费端按消息编号去重。"
            +"Redis 只是加速，数据库失败时需要处理重试。我用同一请求号重复提交、事务回滚和重复消息验证行为，没有测量吞吐量。";
    @DynamicPropertySource static void provider(DynamicPropertyRegistry r) {
        r.add("ai.api-key",()->System.getenv("AI_API_KEY"));
        r.add("ai.deepseek.base-url",()->System.getenv("ACCEPTANCE_BASE_URL"));
        r.add("ai.deepseek.model",()->System.getenv("ACCEPTANCE_MODEL"));
        r.add("ai.deepseek.thinking",()->"disabled");
        r.add("app.redis.rate-limit.host",()->"127.0.0.1");
        r.add("app.redis.rate-limit.port",()->System.getenv("LIFECYCLE_REDIS_PORT"));
        r.add("app.redis.namespace",()->System.getenv("LIFECYCLE_REDIS_NAMESPACE"));
    }
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean InterviewRunStore runs;
    @Autowired InterviewRunWorker worker;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean InterviewRunModel model;
    @Autowired InterviewSessionRepository sessions;
    @Autowired JdbcTemplate jdbc;
    private ObjectNode traceReport;
    private ObjectNode traceRun;
    private ObjectNode traceAttempt;
    private Path traceFile;

    @Test void productionWorkerCompletesOneRoundWithinEightRequests() throws Exception {
        int maxRequests=Integer.parseInt(System.getenv("LIFECYCLE_MAX_REQUESTS"));
        assertThat(maxRequests).isBetween(1,8);
        assertThat(Integer.parseInt(System.getenv("LIFECYCLE_MAX_OUTPUT_TOKENS"))).isEqualTo(maxRequests*2000);
        var reservations=new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation->{
            if(reservations.incrementAndGet()>maxRequests)
                throw new cn.edu.whu.zhizhida.common.execution.RunFailureException("RUN_BUDGET");
            return invocation.callRealMethod();
        }).when(runs).reserveModelAttempt(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(cn.edu.whu.zhizhida.interview.execution.RunContextAssembler.Prepared.class));
        var config=objectMapper.readTree(model.configuration());
        assertThat(config.path("decisionProtocol").asText()).isEqualTo("phased-v2");
        assertThat(config.at("/budgetProfile/execution/maxModelAttempts").asInt()).isEqualTo(4);
        assertThat(config.at("/executionProfile/maxOutputTokens").asInt()).isEqualTo(2000);
        var report=objectMapper.createObjectNode().put("recordedAt",Instant.now().toString())
                .put("scope","real-production-worker-synthetic-one-round-lifecycle")
                .put("maxRequests",maxRequests).put("reservedOutputTokens",maxRequests*2000).put("result","INCOMPLETE");
        report.set("config",config);report.put("material",MATERIAL).put("answer",ANSWER);report.putArray("runs");
        Path directory=Path.of(System.getProperty("provider.report-directory","target"));
        Files.createDirectories(directory);
        Path file=directory.resolve("provider-interview-lifecycle.json");
        traceReport=report;traceFile=file;
        captureModelTrace();
        String token=registerUniqueUserToken("password123","合成完整生命周期");
        long owner=data(performGet("/api/users/me",token),200).path("id").asLong();
        try {
            var opening=execute(owner,data(performPost("/api/interviews/start-commands",Map.of("requestId",UUID.randomUUID().toString(),
                    "configuration",Map.of("targetRole","Java 后端工程师","mode","PROJECT_DEEP_DIVE","totalRounds",1,
                            "resumeSummary","合成练习项目：我负责订单幂等接口与 outbox 事务设计。"+MATERIAL)),token),202),report,file);
            assertThat(opening.status()).as("Opening error: %s",opening.error()).isEqualTo("SUCCEEDED");
            var answer=execute(owner,data(performPost("/api/interviews/sessions/"+opening.sessionId()+"/answer-commands",
                    Map.of("requestId",UUID.randomUUID().toString(),"expectedTurn",1,"answer",ANSWER),token),202),report,file);
            assertThat(answer.status()).as("Answer error: %s",answer.error()).isEqualTo("SUCCEEDED");
            var saved=sessions.findById(owner,opening.sessionId()).orElseThrow();
            report.set("session",objectMapper.valueToTree(saved));
            assertThat(saved.state()).isEqualTo(InterviewState.FINISHED);
            assertThat(saved.agent().evaluations()).hasSize(1);
            assertThat(saved.agent().evaluations().getFirst().assessment()).isNotNull();
            assertThat(saved.turns().getLast().content()).isNotBlank();
            report.put("result","PASSED");
        } finally {
            report.put("totalAttempts",jdbc.queryForObject("SELECT COUNT(*) FROM interview_model_attempts a JOIN interview_runs r ON a.run_id=r.id WHERE r.owner_id=?",Integer.class,owner));
            report.put("completedAt",Instant.now().toString());
            Files.writeString(file,objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            performDeleteWithBody("/api/users/me",Map.of("currentPassword","password123"),token);
        }
        assertThat(report.path("totalAttempts").asInt()).isLessThanOrEqualTo(maxRequests);
    }

    private InterviewRunStore.Row execute(long owner,JsonNode accepted,ObjectNode report,Path file) throws Exception {
        String id=accepted.path("runId").asText();
        var pending=runs.get(owner,id);
        assertThat(pending.route()).isEqualTo("TOOL");
        var result=report.withArray("runs").addObject().put("operation",pending.operation()).put("runId",id).put("status","STARTED");
        traceRun=result;traceAttempt=null;
        result.putArray("modelTrace");
        Files.writeString(file,objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        long started=System.nanoTime();
        try {
            var claim=runs.claim(pending);
            assertThat(claim).isNotNull();
            worker.execute(claim);
        } finally {
            var completed=runs.get(owner,id);
            result.put("status",completed.status()).put("error",completed.error()).put("attempts",completed.attempts())
                    .put("tools",completed.tools()).put("repairs",completed.repairs()).put("elapsedMillis",(System.nanoTime()-started)/1_000_000);
            result.set("attemptLedger",objectMapper.valueToTree(jdbc.queryForList(
                    "SELECT attempt_id,ordinal,status,error_code,usage_json,elapsed_ms,budget_phase FROM interview_model_attempts WHERE run_id=? ORDER BY ordinal",id)));
            result.set("receipts",objectMapper.valueToTree(jdbc.queryForList(
                    "SELECT tool_name,args_json,result_json,execution_kind FROM interview_tool_receipts WHERE run_id=? ORDER BY id",id)));
            if(completed.checkpoint()!=null) {
                var cp=objectMapper.readTree(completed.checkpoint());
                result.put("lastPublicCandidate",cp.path("response").path("content").asText());
                var validation=result.putArray("validationFailures");
                for(var message:cp.path("messages")) if(message.path("role").asText().equals("user")
                        &&message.path("content").asText().startsWith("服务端校验未通过")) validation.add(message.path("content").asText());
            }
            Files.writeString(file,objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
        return runs.get(owner,id);
    }

    private void captureModelTrace() {
        org.mockito.Mockito.doAnswer(invocation->{
            traceAttempt=traceRun.withArray("modelTrace").addObject()
                    .put("attemptId",invocation.<String>getArgument(5))
                    .put("phase",invocation.<String>getArgument(9)).put("startedAt",Instant.now().toString());
            traceAttempt.putArray("validation");
            try {
                var reply=(InterviewRunModel.Reply)invocation.callRealMethod();
                traceAttempt.put("status","RETURNED").put("finish",reply.finish());
                traceAttempt.set("message",reply.message().deepCopy());
                traceAttempt.set("usage",reply.usage().deepCopy());
                return reply;
            } catch (Throwable failure) {
                traceAttempt.put("status","THREW").put("exceptionType",failure.getClass().getName());
                throw failure;
            } finally {
                traceAttempt.put("completedAt",Instant.now().toString());
                saveTrace();
            }
        }).when(model).call(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(cn.edu.whu.zhizhida.interview.api.InterviewSession.class),
                org.mockito.ArgumentMatchers.nullable(cn.edu.whu.zhizhida.interview.domain.InterviewTransitions.PendingAnswer.class),
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doAnswer(invocation->recordValidation("ACTION",invocation))
                .when(model).validateAction(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doAnswer(invocation->recordValidation("FINAL_ENVELOPE",invocation))
                .when(model).finalContent(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doAnswer(invocation->recordValidation("BUSINESS",invocation))
                .when(model).validate(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(InterviewStateMachine.class),
                        org.mockito.ArgumentMatchers.any(cn.edu.whu.zhizhida.interview.api.InterviewSession.class),
                        org.mockito.ArgumentMatchers.nullable(cn.edu.whu.zhizhida.interview.domain.InterviewTransitions.PendingAnswer.class),
                        org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyList());
    }

    private Object recordValidation(String stage,org.mockito.invocation.InvocationOnMock invocation) throws Throwable {
        var validation=traceAttempt.withArray("validation").addObject().put("stage",stage);
        try {
            Object result=invocation.callRealMethod();
            validation.put("status","ACCEPTED");
            return result;
        } catch (InterviewReviewRejected rejected) {
            validation.put("status","REJECTED").put("reason",rejected.reason().name())
                    .put("path",rejected.fieldPath()).put("message",rejected.getMessage())
                    .put("repairFeedback",rejected.repairFeedback());
            throw rejected;
        } finally {
            saveTrace();
        }
    }

    private void saveTrace() throws java.io.IOException {
        Files.writeString(traceFile,objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(traceReport));
    }
    private JsonNode data(MvcResult response,int status) throws Exception {
        assertThat(response.getResponse().getStatus()).isEqualTo(status);
        return objectMapper.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }
}
