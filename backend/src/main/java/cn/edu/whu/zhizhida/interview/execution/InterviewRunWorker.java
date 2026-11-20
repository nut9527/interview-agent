package cn.edu.whu.zhizhida.interview.execution;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import cn.edu.whu.zhizhida.common.MemoryRevokedException;
import cn.edu.whu.zhizhida.common.SourceRevokedException;
import cn.edu.whu.zhizhida.common.UpstreamServiceException;
import cn.edu.whu.zhizhida.common.execution.ExecutionPort;
import cn.edu.whu.zhizhida.common.execution.RunExecutorRegistry;
import cn.edu.whu.zhizhida.common.execution.RunFailureException;
import cn.edu.whu.zhizhida.interview.InterviewRunModel;
import cn.edu.whu.zhizhida.interview.InterviewSessionRepository;
import cn.edu.whu.zhizhida.interview.InterviewSourceGuard;
import cn.edu.whu.zhizhida.interview.InterviewStateMachine;
import cn.edu.whu.zhizhida.interview.api.InterviewSession;
import cn.edu.whu.zhizhida.interview.api.QuestionCatalog;
import cn.edu.whu.zhizhida.learning.api.PracticeMemoryAccess;
import cn.edu.whu.zhizhida.observability.AgentTelemetry;
import cn.edu.whu.zhizhida.observability.AgentTelemetry.Stage;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 从持久化检查点推进任务，通过执行席位控制本机并发。
 *
 * <p>
 * 数据库扫描与 MQ 消费共用领取路径；心跳使用独立线程，模型慢调用不占用续租线程。
 */
@Component
public class InterviewRunWorker {
    private static final Logger log = LoggerFactory.getLogger(InterviewRunWorker.class);
    private static final long HEARTBEAT_INTERVAL_SECONDS = 10;
    private final InterviewSourceGuard sourceGuard;
    private final PracticeMemoryAccess memory;
    private final InterviewRunStore store;
    private final RunExecutorRegistry executors;
    private final InterviewRunModel model;
    private final InterviewContextStage contextStage;
    private final InterviewModelStage modelStage;
    private final InterviewToolStage toolStage;
    private final InterviewValidateStage validateStage;
    private final ObjectMapper json;
    private final boolean enabled;
    private final String dispatchMode;
    private final RunExecutionSlots slots;
    private final AgentTelemetry telemetry;
    private final ExecutorService workers = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual()
                    .name("agent-run-", 0)
                    .inheritInheritableThreadLocals(false)
                    .factory());
    // 每个已准入的 run 各有一个心跳；数量受执行席位限制，慢 DB 调用不阻塞其他 run 的续租。
    private final ExecutorService heartbeats = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual()
                    .name("agent-heartbeat-", 0)
                    .inheritInheritableThreadLocals(false)
                    .factory());

    public InterviewRunWorker(
            InterviewRunStore store,
            InterviewSessionRepository sessions,
            InterviewStateMachine machine,
            InterviewRunModel model,
            InterviewEvidenceTools tools,
            ObjectMapper json,
            RunExecutorRegistry executors,
            @Value("${interview.runs.worker-enabled:true}") boolean enabled,
            RunExecutionSlots slots,
            AgentTelemetry telemetry,
            InterviewSourceGuard sourceGuard,
            PracticeMemoryAccess memory,
            QuestionCatalog questions,
            @Value("${app.run-dispatch.mode:database}") String dispatchMode) {
        if (!Set.of("database", "rabbitmq").contains(dispatchMode))
            throw new IllegalArgumentException("Unsupported run dispatch mode");
        this.store = store;
        this.executors = executors;
        this.model = model;
        this.json = json;
        this.enabled = enabled;
        this.slots = slots;
        this.telemetry = telemetry;
        this.sourceGuard = sourceGuard;
        this.memory = memory;
        this.dispatchMode = dispatchMode;
        var evidence = new RunEvidence(store, tools);
        this.contextStage = new InterviewContextStage(
                store,
                sessions,
                machine,
                model,
                tools,
                json,
                sourceGuard,
                memory,
                questions);
        this.modelStage = new InterviewModelStage(store, model, tools, json, telemetry, evidence);
        this.toolStage = new InterviewToolStage(store, tools, json, sourceGuard, memory, telemetry);
        this.validateStage = new InterviewValidateStage(
                store, machine, model, json, telemetry, evidence, modelStage);
    }

    @Scheduled(scheduler = "runScheduler", fixedDelayString = "${interview.runs.scan-ms:2000}")
    public void scan() {
        if (!enabled || !"database".equals(dispatchMode))
            return;
        for (var row : store.candidates()) {
            var permit = slots.tryAcquire(row.id(), row.owner());
            if (permit == null)
                continue;
            try {
                workers.submit(
                        () -> {
                            try (permit) {
                                claimAndExecute(row);
                            } catch (RuntimeException ex) {
                                logFailure(row.id(), row.epoch(), "CLAIM", "unknown", ex);
                            }
                        });
            } catch (RuntimeException ex) {
                permit.close();
                throw ex;
            }
        }
    }

    /** 返回 true 时可确认消息；容量或退避等待交还 Broker 延迟重试。 */
    public boolean executeCandidate(InterviewRunStore.Row row) {
        try (var permit = slots.tryAcquire(row.id(), row.owner())) {
            if (permit == null)
                return false;
            return claimAndExecute(row);
        }
    }

    /**
     * 取得数据库租约后执行任务，并核对可交还调度方的持久状态。
     *
     * <p>
     * 执行返回后须已到终态或已持久安排重试；否则抛错，由消费端或恢复扫描继续处理。
     */
    private boolean claimAndExecute(InterviewRunStore.Row row) {
        var claim = store.claim(row);
        if (claim == null) {
            // 活跃租约已有执行者；等待容量、退避或过期租约仍须保留通知。
            return !store.needsDispatch(row.id());
        }
        execute(claim);
        var result = store.dispatchCandidate(row.id());
        if (result.isEmpty() || result.get().terminal())
            return true;
        if (!store.retryScheduled(claim))
            throw new IllegalStateException("RUN_NOT_TERMINAL");
        return false;
    }

    @Scheduled(scheduler = "runScheduler", fixedDelayString = "${interview.runs.expire-ms:2000}")
    public void expireWaiting() {
        // RabbitMQ 模式由恢复器同时回收到期任务，避免两个定时器查询同一批行。
        if (enabled && "database".equals(dispatchMode))
            store.expireWaiting();
    }

    @Scheduled(fixedDelay = 60000)
    public void prune() {
        if (enabled)
            store.prune();
    }

    public void execute(InterviewRunStore.Claim c) {
        AgentTelemetry.Parent parent;
        try {
            parent = store.traceParent(c);
        } catch (RuntimeException ignored) {
            parent = null;
        }
        try (var span = telemetry
                .resume(parent)
                .tag("agent.run.id", c.id())
                .tag("agent.lease.epoch", Long.toString(c.epoch()))) {
            execute(c, span);
        }
    }

    private void execute(InterviewRunStore.Claim c, AgentTelemetry.Scope span) {
        boolean contextReady = false;
        Thread runner = Thread.currentThread();
        // 取消续租后迟到的 DB 结果不能再中断已开始处理下一条消息的消费线程。
        var heartbeatActive = new boolean[] { true };
        Runnable interruptRunner = () -> {
            synchronized (heartbeatActive) {
                if (heartbeatActive[0])
                    runner.interrupt();
            }
        };
        var trace = new ExecutionTrace();
        // 心跳失败即中断当前执行；最终写入还会校验租约代次，防止旧执行者提交迟到结果。
        var heartbeat = heartbeats.submit(
                () -> {
                    try {
                        while (!Thread.currentThread().isInterrupted()) {
                            TimeUnit.SECONDS.sleep(HEARTBEAT_INTERVAL_SECONDS);
                            if (!store.heartbeat(c)) {
                                log.debug(
                                        "run.lease_lost runId={} epoch={} phase=HEARTBEAT",
                                        c.id(),
                                        c.epoch());
                                interruptRunner.run();
                                return;
                            }
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException ex) {
                        logFailure(c.id(), c.epoch(), "HEARTBEAT", "unknown", ex);
                        interruptRunner.run();
                    }
                });
        try {
            if (store.remainingMillis(c.id()) <= 0) {
                span.failure("RUN_BUDGET");
                store.fail(c, "RUN_BUDGET");
                return;
            }
            var row = store.get(c.owner(), c.id());
            contextReady = true;
            span.tag("agent.operation", row.operation())
                    .tag("agent.subject.type", row.subjectType())
                    .tag("agent.route", row.route())
                    .tag("agent.resume.phase", row.phase());
            if (row.subjectType().equals("INTERVIEW")) {
                run(c, row, trace);
            } else {
                executors
                        .require(row.subjectType())
                        .execute(
                                new ExecutionPort.Context(c.id(), c.owner(), c.epoch()),
                                row.subjectId(),
                                row.operation(),
                                row.input());
            }
        } catch (RuntimeException ex) {
            span.failure(failureCode(ex));
            logFailure(c.id(), c.epoch(), trace.phase, trace.attemptId, ex);
            try {
                store.fail(c, failureCode(ex));
            } catch (RuntimeException failure) {
                logFailure(c.id(), c.epoch(), "FAIL_COMMIT", trace.attemptId, failure);
                throw failure;
            }
        } finally {
            synchronized (heartbeatActive) {
                heartbeatActive[0] = false;
            }
            heartbeat.cancel(true);
            if (contextReady)
                try {
                    var result = store.get(c.owner(), c.id());
                    span.tag("agent.run.status", result.status());
                    if (result.error() != null)
                        span.failure(result.error());
                } catch (RuntimeException ignored) {
                    /* 诊断读取失败不能改变已提交的业务结果。 */
                }
            Thread.interrupted();
        }
    }

    /** 从持久化检查点恢复执行；模型、工具和结果校验分别推进阶段，业务结果最终统一提交。 */
    private void run(InterviewRunStore.Claim c, InterviewRunStore.Row row, ExecutionTrace trace) {
        trace.phase = "CONTEXT";
        String latestAttempt = store.latestAttemptId(c);
        trace.attemptId = latestAttempt == null ? "none" : latestAttempt;
        trace.phase = "CHECKPOINT";
        ObjectNode cp = InterviewRunCheckpoint.read(json, row.checkpoint(), row.route());
        var budget = model.requireConfiguration(row.config());
        trace.phase = "CONTEXT";

        var input = contextStage.restoreInput(c, row);
        if (validateStage.commitFixedOpening(c, input, trace))
            return;
        var execution = contextStage.prepareContext(c, row, input, budget, cp);
        advanceStages(execution, trace);
    }

    private void advanceStages(RunContext execution, ExecutionTrace trace) {
        var c = execution.claim();
        var cp = execution.checkpoint();
        while (true) {
            // 固定快照并不延长授权；每轮继续执行前重新检查资料与历史观察是否已被撤销。
            sourceGuard.require(c.owner(), execution.session());
            memory.read(c.owner(), execution.memorySelection());
            var row = store.get(c.owner(), c.id());
            trace.phase = "CHECKPOINT";
            InterviewRunCheckpoint.validate(cp, row.route());
            if (cp.path("stage").asString().equals("ACTION_VALIDATE")) {
                // 行动校验只决定进入最终输出或修正行动，不提交面试结果。
                trace.phase = "ACTION_VALIDATE";
                telemetry.observe(
                        Stage.VALIDATE,
                        () -> {
                            validateStage.validateAction(execution);
                            return null;
                        });
                continue;
            }
            String stage = cp.path("stage").asString();
            if (stage.equals("FIXED") || stage.equals("KNOWLEDGE_PREFETCH")) {
                trace.phase = stage;
                var fixedRow = row;
                telemetry.observe(
                        Stage.TOOL,
                        () -> {
                            toolStage.retrieveFixed(execution, fixedRow);
                            return null;
                        });
            }
            // 各阶段会原地更新 cp，因此刻意逐次读取：检索后可直接调用模型，响应落库后可直接消费。
            if (cp.path("stage").asString().equals("MODEL")) {
                if (stage.equals("FIXED") || stage.equals("KNOWLEDGE_PREFETCH"))
                    row = store.get(c.owner(), c.id());
                trace.phase = "MODEL";
                // false 仅表示已安排延迟重试；本次释放执行席位，下次认领从保存的阶段继续。
                if (!modelStage.callModel(execution, row, trace))
                    return;
            }
            if (cp.path("stage").asString().equals("TOOL")) {
                trace.phase = "TOOL";
                // MODEL 调用前重读记录，工具使用相同的尝试前输入哈希。
                toolStage.executeTools(execution, row, trace);
                continue;
            }
            if (cp.path("stage").asString().equals("VALIDATE")) {
                trace.phase = "VALIDATE";
                // 成功提交才结束；首次业务校验失败回到 MODEL 修正，耗尽修正预算则抛错终止。
                if (validateStage.validateAndCommit(execution, trace))
                    return;
                continue;
            }
        }
    }

    static String failureCode(Throwable failure) {
        if (failure instanceof InterviewRunCheckpoint.Invalid)
            return "RUN_CHECKPOINT_INVALID";
        if (failure instanceof MemoryRevokedException)
            return "RUN_MEMORY_REVOKED";
        if (failure instanceof SourceRevokedException)
            return "RUN_SOURCE_REVOKED";
        if (failure instanceof RunFailureException classified)
            return classified.code();
        if (failure instanceof UpstreamServiceException upstream && upstream.code() != null)
            return upstream.code();
        return "RUN_EXECUTION_FAILED";
    }

    static String modelFailureCode(Throwable failure) {
        return cn.edu.whu.zhizhida.common.execution.AgentExecution.modelFailureCode(failure);
    }

    static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static void logFailure(
            String runId, long epoch, String phase, String attemptId, Throwable failure) {
        String code = failureCode(failure);
        if (failure instanceof InterviewRunCheckpoint.Invalid invalid) {
            log.warn(
                    "run.checkpoint_rejected runId={} attemptId={} epoch={} phase=CHECKPOINT"
                            + " code={} reason={} fieldPath={}",
                    runId,
                    attemptId,
                    epoch,
                    code,
                    invalid.reason(),
                    invalid.path());
            return;
        }
        var level = Set.of(
                "RUN_FENCED",
                "RUN_GONE",
                "RUN_MEMORY_REVOKED",
                "RUN_SOURCE_REVOKED",
                "MODEL_INTERRUPTED")
                .contains(code)
                        ? log.atDebug()
                        : log.atWarn();
        // 不附加 Throwable，其消息、原因和受抑制异常可能含材料正文或凭据。
        level.log(
                "run.worker_failed runId={} attemptId={} epoch={} phase={} code={} exceptionType={}"
                        + " location={}",
                runId,
                attemptId,
                epoch,
                phase,
                code,
                failure.getClass().getName(),
                location(failure));
    }

    static String location(Throwable failure) {
        var frames = failure.getStackTrace();
        if (frames.length == 0)
            return "unknown";
        var first = frames[0];
        return first.getClassName() + "." + first.getMethodName() + ":" + first.getLineNumber();
    }

    void commit(InterviewRunStore.Claim claim, InterviewSession candidate, boolean opening) {
        validateStage.commit(claim, candidate, opening);
    }

    @PreDestroy
    public void close() {
        workers.shutdownNow();
        heartbeats.shutdownNow();
    }
}
