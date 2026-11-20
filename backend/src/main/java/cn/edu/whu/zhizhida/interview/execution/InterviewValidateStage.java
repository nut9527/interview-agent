package cn.edu.whu.zhizhida.interview.execution;

import cn.edu.whu.zhizhida.common.execution.RunFailureException;
import cn.edu.whu.zhizhida.interview.InterviewDecisionContract;
import cn.edu.whu.zhizhida.interview.InterviewReviewRejected;
import cn.edu.whu.zhizhida.interview.InterviewRunModel;
import cn.edu.whu.zhizhida.interview.InterviewStateMachine;
import cn.edu.whu.zhizhida.interview.api.InterviewSession;
import cn.edu.whu.zhizhida.interview.api.KnowledgeSnapshot;
import cn.edu.whu.zhizhida.observability.AgentTelemetry;
import cn.edu.whu.zhizhida.observability.AgentTelemetry.Stage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

final class InterviewValidateStage {
    private static final Logger log = LoggerFactory.getLogger(InterviewRunWorker.class);
    private static final int MAX_COMMIT_RETRIES = 2;
    private static final long COMMIT_RETRY_DELAY_MILLIS = 10;
    private final InterviewRunStore store;
    private final InterviewStateMachine machine;
    private final InterviewRunModel model;
    private final ObjectMapper json;
    private final AgentTelemetry telemetry;
    private final RunEvidence evidence;
    private final InterviewModelStage modelStage;

    InterviewValidateStage(
            InterviewRunStore store,
            InterviewStateMachine machine,
            InterviewRunModel model,
            ObjectMapper json,
            AgentTelemetry telemetry,
            RunEvidence evidence,
            InterviewModelStage modelStage) {
        this.store = store;
        this.machine = machine;
        this.model = model;
        this.json = json;
        this.telemetry = telemetry;
        this.evidence = evidence;
        this.modelStage = modelStage;
    }

    boolean commitFixedOpening(InterviewRunStore.Claim c, RunInput input, ExecutionTrace trace) {
        if (!input.opening()) return false;
        String question = machine.fixedOpening(input.session());
        if (question == null) return false;
        trace.phase = "COMMIT";
        telemetry.observe(
                Stage.COMMIT,
                () -> {
                    commit(c, machine.opened(input.session(), question), true);
                    return null;
                });
        return true;
    }

    void validateAction(RunContext execution) {
        var cp = execution.checkpoint();
        try {
            var action = model.validateAction(cp.path("response").path("content").asString());

            EvidenceReview.validate(
                    action, evidence.evidenceSources(execution), execution.opening());
            boolean revise = EvidenceReview.apply(cp, action);
            if (revise && !cp.path("actionRepaired").asBoolean()) {
                cp.put("stage", "MODEL");
                cp.withArray("messages")
                        .add(
                                json.createObjectNode()
                                        .put("role", "user")
                                        .put(
                                                "content",
                                                "根据检查中未解决的主张执行下一次取证；完成后重新提交完整"
                                                    + " checks。已用过修订机会，下一次行动对象须为 COMPLETE。"));
                store.checkpoint(
                        execution.claim(),
                        cp.toString(),
                        "MODEL",
                        "evidence.revising",
                        "发现证据缺口，正在调整取证");
                return;
            }
        } catch (InterviewReviewRejected ex) {
            var row = store.get(execution.claim().owner(), execution.claim().id());
            var budget = execution.budget();
            if (cp.path("actionRepaired").asBoolean()
                    || row.attempts() >= budget.attemptLimit("ACTION", false))
                throw new RunFailureException("MODEL_ACTION_INVALID");
            cp.put("actionRepaired", true).put("stage", "MODEL");
            cp.withArray("messages")
                    .add(
                            json.createObjectNode()
                                    .put("role", "user")
                                    .put(
                                            "content",
                                            InterviewDecisionContract.ACTION_REPAIR.formatted(
                                                    ex.repairFeedback())));
            store.checkpoint(
                    execution.claim(), cp.toString(), "MODEL", "action.repairing", "正在核对行动格式");
            return;
        }
        modelStage.enterFinal(execution);
    }

    boolean validateAndCommit(RunContext execution, ExecutionTrace trace) {
        var c = execution.claim();
        var cp = execution.checkpoint();
        var session = execution.session();
        var pending = execution.pending();
        var context = execution.context();
        boolean opening = execution.opening();
        store.checkpoint(c, cp.toString(), "VALIDATE", "output.validating", "正在校验本轮结果");
        InterviewSession candidate;
        try {
            candidate =
                    telemetry.observe(
                            Stage.VALIDATE,
                            () -> {
                                String content = cp.path("response").path("content").asString();
                                content = model.finalContent(content);
                                EvidenceReview.validateFinal(
                                        json.readTree(content),
                                        cp.path("evidenceReview"),
                                        evidence.evidenceSources(execution));
                                var verified = evidence.verifiedReviewSources(execution);
                                return verified.isEmpty()
                                        ? model.validate(
                                                c.owner(), machine, session, pending, context,
                                                content)
                                        : model.validate(
                                                c.owner(), machine, session, pending, context,
                                                content, verified);
                            });
        } catch (InterviewReviewRejected ex) {
            log.warn(
                    "run.validation_rejected runId={} attemptId={} epoch={} phase=VALIDATE"
                            + " code=MODEL_OUTPUT_INVALID reason={} fieldPath={} repaired={}",
                    c.id(),
                    trace.attemptId,
                    c.epoch(),
                    ex.reason(),
                    ex.fieldPath(),
                    cp.path("repaired").asBoolean());
            // 仅允许一次业务输出修正，并复用既有材料和回执；其他故障不走此分支。
            if (cp.path("repaired").asBoolean())
                throw new RunFailureException("MODEL_OUTPUT_INVALID");
            cp.put("repaired", true).put("stage", "MODEL");
            ((ArrayNode) cp.path("messages"))
                    .add(
                            json.createObjectNode()
                                    .put("role", "user")
                                    .put(
                                            "content",
                                            InterviewRunModel.repairPrompt(ex)
                                                    + InterviewDecisionContract.FINAL_REPAIR));
            store.repair(c, cp.toString());
            log.info(
                    "run.repair_scheduled runId={} attemptId={} epoch={} phase=VALIDATE",
                    c.id(),
                    trace.attemptId,
                    c.epoch());
            return false;
        }
        List<KnowledgeSnapshot.Reference> sources =
                json.convertValue(cp.path("sources"), new TypeReference<>() {});
        if (pending != null && candidate.agent() != null)
            candidate =
                    candidate.withAgent(
                            candidate
                                    .agent()
                                    .withKnowledgeSources(pending.answeredCount(), sources));
        if (candidate.agent() != null && cp.has("memoryUses"))
            candidate =
                    candidate.withAgent(
                            candidate
                                    .agent()
                                    .withMemoryReferences(
                                            json.convertValue(
                                                    cp.path("memoryUses"),
                                                    new TypeReference<>() {})));
        trace.phase = "COMMIT";
        var completed = candidate;
        telemetry.observe(
                Stage.COMMIT,
                () -> {
                    commit(c, completed, opening);
                    return null;
                });
        return true;
    }

    void commit(InterviewRunStore.Claim claim, InterviewSession candidate, boolean opening) {
        for (int attempt = 0; ; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new RunFailureException("RUN_FENCED");
            try {
                store.commit(claim, candidate, opening);
                return;
            } catch (PessimisticLockingFailureException ex) {
                // 通常由 store.commit 独立开启事务；若已加入外层事务，此处不能安全重启。
                if (attempt >= MAX_COMMIT_RETRIES
                        || TransactionSynchronizationManager.isActualTransactionActive()) throw ex;
                log.warn(
                        "run.commit_retry runId={} epoch={} retry={} exceptionType={}",
                        claim.id(),
                        claim.epoch(),
                        attempt + 1,
                        ex.getClass().getName());
                LockSupport.parkNanos(
                        TimeUnit.MILLISECONDS.toNanos(COMMIT_RETRY_DELAY_MILLIS * (attempt + 1)));
                // 下一次提交仍须重新核对 epoch、租约、取消状态与原始截止时间。
            }
        }
    }
}
