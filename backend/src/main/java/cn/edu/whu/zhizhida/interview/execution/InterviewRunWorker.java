package cn.edu.whu.zhizhida.interview.execution;

import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.common.MemoryRevokedException;
import cn.edu.whu.zhizhida.common.SourceRevokedException;
import cn.edu.whu.zhizhida.common.UpstreamServiceException;
import cn.edu.whu.zhizhida.common.execution.ExecutionPort;
import cn.edu.whu.zhizhida.common.execution.RunExecutorRegistry;
import cn.edu.whu.zhizhida.common.execution.RunFailureException;
import cn.edu.whu.zhizhida.interview.InterviewDecisionContract;
import cn.edu.whu.zhizhida.interview.InterviewReviewRejected;
import cn.edu.whu.zhizhida.interview.InterviewRunModel;
import cn.edu.whu.zhizhida.interview.InterviewSessionRepository;
import cn.edu.whu.zhizhida.interview.InterviewSourceGuard;
import cn.edu.whu.zhizhida.interview.InterviewStateMachine;
import cn.edu.whu.zhizhida.interview.api.InterviewAgentState.MemoryReference;
import cn.edu.whu.zhizhida.interview.api.InterviewRun;
import cn.edu.whu.zhizhida.interview.api.InterviewSession;
import cn.edu.whu.zhizhida.interview.api.InterviewSession.InterviewTurn;
import cn.edu.whu.zhizhida.interview.api.KnowledgeSnapshot;
import cn.edu.whu.zhizhida.interview.api.QuestionCatalog;
import cn.edu.whu.zhizhida.interview.domain.InterviewTransitions;
import cn.edu.whu.zhizhida.learning.api.PracticeMemoryAccess;
import cn.edu.whu.zhizhida.observability.AgentTelemetry;
import cn.edu.whu.zhizhida.observability.AgentTelemetry.Stage;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * 从持久化检查点推进任务，通过执行席位控制本机并发。
 *
 * <p>数据库扫描与 MQ 消费共用领取路径；心跳使用独立线程，模型慢调用不占用续租线程。
 */
@Component
public class InterviewRunWorker {
    private static final Logger log = LoggerFactory.getLogger(InterviewRunWorker.class);
    private static final long HEARTBEAT_INTERVAL_SECONDS = 10;
    private static final int MAX_COMMIT_RETRIES = 2;
    private static final long COMMIT_RETRY_DELAY_MILLIS = 10;
    private final InterviewSourceGuard sourceGuard;
    private final PracticeMemoryAccess memory;
    private final QuestionCatalog questions;
    private final InterviewRunStore store;
    private final RunExecutorRegistry executors;
    private final InterviewSessionRepository sessions;
    private final InterviewStateMachine machine;
    private final InterviewRunModel model;
    private final InterviewEvidenceTools tools;
    private final ObjectMapper json;
    private final boolean enabled;
    private final String dispatchMode;
    private final RunExecutionSlots slots;
    private final AgentTelemetry telemetry;
    private final ExecutorService workers =
            Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual()
                            .name("agent-run-", 0)
                            .inheritInheritableThreadLocals(false)
                            .factory());
    // 每个已准入的 run 各有一个心跳；数量受执行席位限制，慢 DB 调用不阻塞其他 run 的续租。
    private final ExecutorService heartbeats =
            Executors.newThreadPerTaskExecutor(
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
        this.sessions = sessions;
        this.machine = machine;
        this.model = model;
        this.tools = tools;
        this.json = json;
        this.enabled = enabled;
        this.slots = slots;
        this.telemetry = telemetry;
        this.sourceGuard = sourceGuard;
        this.memory = memory;
        this.questions = questions;
        this.dispatchMode = dispatchMode;
    }

    @Scheduled(scheduler = "runScheduler", fixedDelayString = "${interview.runs.scan-ms:2000}")
    public void scan() {
        if (!enabled || !"database".equals(dispatchMode)) return;
        for (var row : store.candidates()) {
            var permit = slots.tryAcquire(row.id(), row.owner());
            if (permit == null) continue;
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
            if (permit == null) return false;
            return claimAndExecute(row);
        }
    }

    /**
     * 取得数据库租约后执行任务，并核对可交还调度方的持久状态。
     *
     * <p>执行返回后须已到终态或已持久安排重试；否则抛错，由消费端或恢复扫描继续处理。
     */
    private boolean claimAndExecute(InterviewRunStore.Row row) {
        var claim = store.claim(row);
        if (claim == null) {
            // 活跃租约已有执行者；等待容量、退避或过期租约仍须保留通知。
            return !store.needsDispatch(row.id());
        }
        execute(claim);
        var result = store.dispatchCandidate(row.id());
        if (result.isEmpty() || result.get().terminal()) return true;
        if (!store.retryScheduled(claim))
            throw new IllegalStateException("RUN_NOT_TERMINAL");
        return false;
    }

    @Scheduled(scheduler = "runScheduler", fixedDelayString = "${interview.runs.expire-ms:2000}")
    public void expireWaiting() {
        // RabbitMQ 模式由恢复器同时回收到期任务，避免两个定时器查询同一批行。
        if (enabled && "database".equals(dispatchMode)) store.expireWaiting();
    }

    @Scheduled(fixedDelay = 60000)
    public void prune() {
        if (enabled) store.prune();
    }

    public void execute(InterviewRunStore.Claim c) {
        AgentTelemetry.Parent parent;
        try {
            parent = store.traceParent(c);
        } catch (RuntimeException ignored) {
            parent = null;
        }
        try (var span =
                telemetry
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
        var heartbeatActive = new boolean[] {true};
        Runnable interruptRunner =
                () -> {
                    synchronized (heartbeatActive) {
                        if (heartbeatActive[0]) runner.interrupt();
                    }
                };
        var trace = new ExecutionTrace();
        // 心跳失败即中断当前执行；最终写入还会校验租约代次，防止旧执行者提交迟到结果。
        var heartbeat =
                heartbeats.submit(
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
                run(c, trace);
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
                    if (result.error() != null) span.failure(result.error());
                } catch (RuntimeException ignored) {
                    /* 诊断读取失败不能改变已提交的业务结果。 */
                }
            Thread.interrupted();
        }
    }

    /** 从持久化检查点恢复执行；模型、工具和结果校验分别推进阶段，业务结果最终统一提交。 */
    private void run(InterviewRunStore.Claim c, ExecutionTrace trace) {
        trace.phase = "CONTEXT";
        var row = store.get(c.owner(), c.id());
        String latestAttempt = store.latestAttemptId(c);
        trace.attemptId = latestAttempt == null ? "none" : latestAttempt;
        trace.phase = "CHECKPOINT";
        ObjectNode cp = InterviewRunCheckpoint.read(json, row.checkpoint(), row.route());
        var budget = model.requireConfiguration(row.config());
        // 分阶段协议与旧协议的响应含义不同，恢复时不能用当前协议解释另一种检查点。
        if (cp.has("stage") && model.phased() != cp.has("outputPhase"))
            throw new RunFailureException("RUN_CHECKPOINT_INVALID");
        trace.phase = "CONTEXT";
        var input = restoreInput(c, row);
        if (commitFixedOpening(c, input, trace)) return;
        var execution = prepareContext(c, row, input, budget, cp);
        advanceStages(execution, trace);
    }

    private RunInput restoreInput(InterviewRunStore.Claim c, InterviewRunStore.Row row) {
        boolean opening = row.operation().equals("START");
        var input = json.readTree(row.input());
        var memorySelection =
                input.has("practiceMemory")
                        ? json.treeToValue(
                                input.path("practiceMemory"), PracticeMemoryAccess.Selection.class)
                        : PracticeMemoryAccess.Selection.none();
        InterviewSession session =
                opening
                        ? json.treeToValue(input.path("session"), InterviewSession.class)
                        : sessions.findById(c.owner(), c.sessionId())
                                .orElseThrow(() -> new RunFailureException("RUN_SESSION_GONE"));
        sourceGuard.require(c.owner(), session);
        if (opening) requireOpeningQuestion(c.owner(), session);
        memory.read(c.owner(), memorySelection);
        var pending = opening ? null : pendingAnswer(session, input.path("answer"));
        return new RunInput(input, session, pending, opening, memorySelection);
    }

    private void requireOpeningQuestion(long owner, InterviewSession session) {
        // 排队中的开场尚未创建会话，须重新核对题目资格；已开始的会话继续使用固定快照。
        if (session.agent() == null
                || session.agent().training() == null
                || session.agent().training().question() == null) return;
        var training = session.agent().training();
        var question = training.question();
        Integer expectedVersion = training.retry() == null ? question.version() : null;
        try {
            questions.requirePublishedForExecution(owner, question.id(), expectedVersion);
        } catch (BusinessException ex) {
            throw new SourceRevokedException();
        }
    }

    private InterviewTransitions.PendingAnswer pendingAnswer(
            InterviewSession session, JsonNode input) {
        var answer = json.treeToValue(input, InterviewRun.Answer.class);
        var decision =
                new InterviewTransitions()
                        .answer(
                                session,
                                answer.answer(),
                                answer.requestId(),
                                answer.expectedTurn());
        if (!(decision instanceof InterviewTransitions.PendingAnswer pending))
            throw new RunFailureException("RUN_TURN_CHANGED");
        return pending;
    }

    private boolean commitFixedOpening(
            InterviewRunStore.Claim c, RunInput input, ExecutionTrace trace) {
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

    private RunContext prepareContext(
            InterviewRunStore.Claim c,
            InterviewRunStore.Row row,
            RunInput input,
            BudgetProfile budget,
            ObjectNode cp) {
        var session = input.session();
        var pending = input.pending();
        List<InterviewEvidenceTools.Section> materials =
                json.convertValue(
                        input.opening()
                                ? input.payload().path("materials")
                                : json.readTree(store.materials(c.owner(), c.sessionId())),
                        new TypeReference<>() {});
        List<InterviewTurn> turns = pending == null ? session.turns() : pending.turns();
        String query =
                turns.stream()
                        .filter(t -> t.speaker().equals("interviewer"))
                        .reduce((a, b) -> b)
                        .map(InterviewTurn::content)
                        .orElse(session.targetRole());
        var fixedSources =
                row.route().equals("LEGACY")
                        ? tools.fixed(session, query)
                        : List.<KnowledgeSnapshot.Reference>of();
        String context = machine.runContext(session, pending, fixedSources);
        boolean prefetch =
                row.route().equals("TOOL") && needsKnowledgePrefetch(tools.knowledge(session));
        if ("KNOWLEDGE_PREFETCH".equals(cp.path("stage").asText()) && !prefetch)
            throw new RunFailureException("RUN_CHECKPOINT_INVALID");
        var execution =
                new RunContext(
                        c,
                        session,
                        pending,
                        input.opening(),
                        input.memorySelection(),
                        materials,
                        turns,
                        query,
                        budget,
                        context,
                        cp);
        // 只为新任务构造初始消息；恢复任务沿用已持久化的模型响应、工具回执和修正次数。
        if (!cp.has("stage")) initializeCheckpoint(execution, row.route(), fixedSources, prefetch);
        return execution;
    }

    private void initializeCheckpoint(
            RunContext execution,
            String route,
            List<KnowledgeSnapshot.Reference> fixedSources,
            boolean prefetch) {
        var cp = execution.checkpoint();
        var memorySelection = execution.memorySelection();
        String stage = route.equals("FIXED") ? "FIXED" : "MODEL";
        cp.put("stage", prefetch ? "KNOWLEDGE_PREFETCH" : stage).put("repaired", false);
        cp.set("sources", json.valueToTree(fixedSources));
        var manifest =
                (ObjectNode)
                        tools.manifest(
                                execution.session(), execution.turns(), execution.materials());
        if (memorySelection.available()) {
            cp.set("practiceMemory", json.valueToTree(memorySelection));
            manifest.set("historyCompetencies", json.valueToTree(memorySelection.competencies()));
            manifest.put("historySamples", memorySelection.references().size())
                    .put("historyVersion", memorySelection.version());
        }
        if (model.phased()) {
            String phase = route.equals("TOOL") ? "ACTION" : "FINAL";
            cp.put("outputPhase", phase).put("actionRepaired", false);
            cp.set(
                    "messages",
                    json.valueToTree(
                            model.phaseMessages(
                                    execution.session(),
                                    execution.pending(),
                                    execution.context(),
                                    manifest,
                                    phase)));
        } else {
            cp.set(
                    "messages",
                    json.valueToTree(
                            model.messages(
                                    execution.session(),
                                    execution.pending(),
                                    execution.context(),
                                    manifest)));
        }
        store.checkpoint(execution.claim(), cp.toString(), "CONTEXT", "context.ready", "面试材料已固定");
    }

    /**
     * 检查点的 stage 表示恢复后待执行的阶段，trace.phase 仅用于诊断当前操作。
     * 主路径为可选检索 → MODEL → TOOL → MODEL，或 MODEL → VALIDATE → 提交；
     * 分阶段协议还会经过 ACTION_VALIDATE，再进入 FINAL 模型调用。
     */
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
            if (cp.path("stage").asText().equals("ACTION_VALIDATE")) {
                // 行动校验只决定进入最终输出或修正行动，不提交面试结果。
                trace.phase = "ACTION_VALIDATE";
                telemetry.observe(
                        Stage.VALIDATE,
                        () -> {
                            validateAction(execution);
                            return null;
                        });
                continue;
            }
            String stage = cp.path("stage").asText();
            if (stage.equals("FIXED") || stage.equals("KNOWLEDGE_PREFETCH")) {
                trace.phase = stage;
                var fixedRow = row;
                telemetry.observe(
                        Stage.TOOL,
                        () -> {
                            retrieveFixed(execution, fixedRow);
                            return null;
                        });
            }
            // 各阶段会原地更新 cp，因此刻意逐次读取：检索后可直接调用模型，响应落库后可直接消费。
            if (cp.path("stage").asText().equals("MODEL")) {
                trace.phase = "MODEL";
                // false 仅表示已安排延迟重试；本次释放执行席位，下次认领从保存的阶段继续。
                if (!callModel(execution, trace)) return;
            }
            if (cp.path("stage").asText().equals("TOOL")) {
                trace.phase = "TOOL";
                // MODEL 调用前重读记录，工具使用相同的尝试前输入哈希。
                executeTools(execution, row, trace);
                continue;
            }
            if (cp.path("stage").asText().equals("VALIDATE")) {
                trace.phase = "VALIDATE";
                // 成功提交才结束；首次业务校验失败回到 MODEL 修正，耗尽修正预算则抛错终止。
                if (validateAndCommit(execution, trace)) return;
                continue;
            }
        }
    }

    private record RunInput(
            JsonNode payload,
            InterviewSession session,
            InterviewTransitions.PendingAnswer pending,
            boolean opening,
            PracticeMemoryAccess.Selection memorySelection) {}

    /** 固定 Run 输入集中保存，持久阶段之间仅推进检查点。 */
    private record RunContext(
            InterviewRunStore.Claim claim,
            InterviewSession session,
            InterviewTransitions.PendingAnswer pending,
            boolean opening,
            PracticeMemoryAccess.Selection memorySelection,
            List<InterviewEvidenceTools.Section> materials,
            List<InterviewTurn> turns,
            String query,
            BudgetProfile budget,
            String context,
            ObjectNode checkpoint) {}

    private static final class ExecutionTrace {
        private String phase = "DISPATCH";
        private String attemptId = "unknown";
    }

    static boolean needsKnowledgePrefetch(List<KnowledgeSnapshot> selected) {
        return selected.stream().anyMatch(doc -> doc.content() != null && !doc.content().isBlank())
                && selected.stream()
                        .flatMap(doc -> doc.excerpts().stream())
                        .noneMatch(
                                excerpt ->
                                        excerpt.content() != null && !excerpt.content().isBlank());
    }

    /**
     * 从本轮已保存的检索回执重建评分来源，检查点中的来源列表仅作为是否需要核验的判断。
     *
     * <p>评分使用经版本和原文核对的回执，模型消息中的引用本身不授予取证资格。
     */
    private List<KnowledgeSnapshot.Reference> verifiedReviewSources(RunContext execution) {
        if (execution.pending() == null || execution.checkpoint().path("sources").isEmpty())
            return List.of();
        return tools.verifiedExcerpts(
                execution.session(), store.knowledgeReceipts(execution.claim()));
    }

    private void retrieveFixed(RunContext execution, InterviewRunStore.Row row) {
        var c = execution.claim();
        var cp = execution.checkpoint();
        var query = execution.query();
        var session = execution.session();
        var turns = execution.turns();
        var materials = execution.materials();
        var policy = execution.budget().execution();
        if (!cp.path("fixedReserved").asBoolean()) {
            cp.put("fixedReserved", true);
            store.batch(c, 1, cp.toString());
        }
        var args =
                json.createObjectNode()
                        .put(
                                "query",
                                query.substring(0, InterviewEvidenceTools.safeEnd(query, 500)))
                        .put("topK", 6);
        var result =
                tools.execute(
                                session,
                                turns,
                                "search_knowledge",
                                args,
                                materials,
                                execution.memorySelection(),
                                memory.read(c.owner(), execution.memorySelection()),
                                execution
                                        .budget()
                                        .toolAvailableMillis(
                                                cp.path("outputPhase").asText("LEGACY"),
                                                store.remainingMillis(c.id())))
                        .payload();
        sourceGuard.require(c.owner(), session);
        memory.read(c.owner(), execution.memorySelection());
        cp.set("sources", result.path("references"));
        ((ArrayNode) cp.path("messages"))
                .add(
                        json.createObjectNode()
                                .put("role", "user")
                                .put("content", "应用固定检索所得的不可信证据：" + result));
        cp.put("stage", "MODEL");
        var observation =
                policy.explicit()
                        ? AgentLoopState.observe(json, cp, "search_knowledge", args, result)
                        : null;
        store.receipt(
                c,
                InterviewRunStore.sha(row.inputHash() + "fixed-search"),
                "fixed-search-1",
                "search_knowledge",
                args.toString(),
                row.inputHash(),
                result.toString(),
                cp.toString(),
                false,
                observation);
    }

    /** false 表示重试已持久安排，应释放执行名额并停止心跳。 */
    private boolean callModel(RunContext execution, ExecutionTrace trace) {
        var c = execution.claim();
        var cp = execution.checkpoint();
        var row = store.get(c.owner(), c.id());
        var policy = execution.budget().execution();
        var memorySelection = execution.memorySelection();
        var session = execution.session();
        var pending = execution.pending();
        var context = execution.context();
        var budget = execution.budget();
        long remaining = store.remainingMillis(c.id());
        if (remaining < AgentLoopPolicy.MIN_MODEL_BUDGET_MILLIS)
            throw new RunFailureException("RUN_BUDGET");
        boolean phased = cp.has("outputPhase");
        if (phased && cp.path("outputPhase").asText().equals("ACTION")) {
            boolean exhausted =
                    row.attempts() >= budget.attemptLimit("ACTION", false)
                            || row.batches() >= policy.maxToolBatches()
                            || row.tools() >= policy.maxToolExecutions()
                            || remaining
                                    < budget.actionMinimumMillis()
                                            + AgentLoopPolicy.COMMIT_RESERVE_MILLIS
                            || cp.path("loop").has("stopReason");
            if (exhausted) {
                if (cp.path("actionRepaired").asBoolean())
                    throw new RunFailureException("MODEL_ACTION_INVALID");
                enterFinal(execution);
            }
        }
        if (policy.explicit() && !cp.path("loop").has("stopReason")) {
            String boundary =
                    policy.boundary(
                            cp.path("loop").path("steps").asInt(),
                            row.attempts(),
                            row.batches(),
                            row.tools(),
                            remaining);
            if (boundary != null) cp.withObject("loop").put("stopReason", boundary);
        }
        if (policy.explicit()
                && cp.path("loop").has("stopReason")
                && !cp.path("loop").path("closed").asBoolean()) {
            cp.withObject("loop").put("closed", true);
            cp.withArray("messages")
                    .add(
                            json.createObjectNode()
                                    .put("role", "user")
                                    .put(
                                            "content",
                                            InterviewRunModel.stopPrompt(
                                                    cp.path("loop").path("stopReason").asText())));
            store.checkpoint(c, cp.toString(), "MODEL", "loop.stopped", "已停止继续取证，正在核对已有结果");
        }
        // 为最终输出保留一次模型尝试；修正输出或触及停止条件后不再开放工具。
        boolean allowed =
                row.route().equals("TOOL")
                        && !cp.path("repaired").asBoolean()
                        && row.batches() < policy.maxToolBatches()
                        && row.tools() < policy.maxToolExecutions()
                        && row.attempts() < policy.maxModelAttempts() - 1;
        if (policy.explicit() && cp.path("loop").has("stopReason")) allowed = false;
        if (phased) {
            // 阶段选择前也应用显式步边界，修正不得重新开放工具。
            if (cp.path("outputPhase").asText().equals("ACTION") && !allowed) enterFinal(execution);
            allowed =
                    cp.path("outputPhase").asText().equals("ACTION")
                            && !cp.path("actionRepaired").asBoolean();
        }
        long timeout =
                budget.callTimeout(
                        phased ? cp.path("outputPhase").asText() : "LEGACY",
                        cp.path("repaired").asBoolean(),
                        remaining);
        var verified = verifiedReviewSources(execution);
        if (!verified.isEmpty())
            model.projectRetrievedSources(
                    c.owner(), session, pending, context, verified, cp.withArray("messages"));
        List<JsonNode> messages = json.convertValue(cp.path("messages"), new TypeReference<>() {});
        List<JsonNode> definitions =
                (phased ? allowed : row.route().equals("TOOL"))
                        ? tools.definitions(memorySelection)
                        : List.of();
        // 预占尝试前编译输入，被拒输入不消耗模型或全局请求名额。
        var prepared =
                new RunContextAssembler(json)
                        .assemble(row.config(), messages, definitions, allowed);
        var reservation = store.reserveModelAttempt(c, prepared);
        String attempt = reservation.id();
        timeout = Math.min(timeout, reservation.timeoutMillis());
        trace.attemptId = attempt;
        log.info(
                "run.model_started runId={} attemptId={} epoch={} phase=MODEL",
                c.id(),
                attempt,
                c.epoch());
        long started = System.nanoTime();
        InterviewRunModel.Reply reply;
        var modelConfig = json.readTree(row.config());
        try (var modelSpan =
                telemetry
                        .start(Stage.MODEL)
                        .tag("agent.run.id", c.id())
                        .tag("agent.attempt.id", attempt)
                        .tag("agent.output.phase", cp.path("outputPhase").asText("LEGACY"))
                        .tag("gen_ai.operation.name", "chat")
                        .tag("gen_ai.provider.name", modelConfig.path("provider").asText())
                        .tag(
                                "gen_ai.request.model",
                                modelConfig.path("model").path("model").asText())
                        .tag(
                                "agent.protocol.version",
                                modelConfig.path("model").path("protocolVersion").asText())
                        .tag("agent.usage.status", "MISSING")) {
            try {
                reply =
                        phased
                                ? model.call(
                                        row.config(),
                                        prepared.messages(),
                                        prepared.tools(),
                                        allowed,
                                        timeout,
                                        attempt,
                                        session,
                                        pending,
                                        context,
                                        cp.path("outputPhase").asText())
                                : model.call(
                                        row.config(),
                                        prepared.messages(),
                                        prepared.tools(),
                                        allowed,
                                        timeout,
                                        attempt,
                                        session,
                                        pending,
                                        context);
                modelSpan.usage(reply.usage());
            } catch (RuntimeException ex) {
                String error = modelFailureCode(ex);
                modelSpan.failure(error);
                long elapsed = elapsedMillis(started);
                log.warn(
                        "run.model_failed runId={} attemptId={} epoch={} phase=MODEL code={}"
                            + " elapsedMs={} exceptionType={} location={}",
                        c.id(),
                        attempt,
                        c.epoch(),
                        error,
                        elapsed,
                        ex.getClass().getName(),
                        location(ex));
                if (ModelRetryPolicy.retryable(error)
                        && (!phased || cp.path("outputPhase").asText().equals("ACTION"))) {
                    if (store.deferModelFailure(c, attempt, error, elapsed, cp.toString()))
                        return false;
                } else store.attemptFailure(c, attempt, error, elapsed);
                throw new RunFailureException(error, ex);
            }
        }
        boolean requested = reply.finish().equals("tool_calls");
        ((ArrayNode) cp.path("messages")).add(reply.message());
        cp.set("response", reply.message());
        String nextStage =
                requested
                        ? "TOOL"
                        : phased && cp.path("outputPhase").asText().equals("ACTION")
                                ? "ACTION_VALIDATE"
                                : "VALIDATE";
        cp.put("stage", nextStage).put("toolIndex", 0).put("batchReserved", false);
        // 响应与后继阶段一起持久化，恢复到 TOOL / VALIDATE 时直接消费该响应，不重新请求模型。
        store.response(
                c,
                attempt,
                json.writeValueAsString(reply),
                reply.usage().toString(),
                elapsedMillis(started),
                cp.toString(),
                nextStage);
        // 工具响应被拒绝时仍先保存计费用量和原始响应，再终结运行。
        if (requested && !allowed) throw new RunFailureException("TOOL_DISABLED");
        log.info(
                "run.model_returned runId={} attemptId={} epoch={} phase=MODEL nextPhase={}"
                    + " elapsedMs={}",
                c.id(),
                attempt,
                c.epoch(),
                nextStage,
                elapsedMillis(started));
        return true;
    }

    private void enterFinal(RunContext execution) {
        model.enterFinal(
                execution.checkpoint(),
                execution.session(),
                execution.pending(),
                execution.context());
        store.checkpoint(
                execution.claim(),
                execution.checkpoint().toString(),
                "MODEL",
                "output.finalizing",
                "取证已结束，正在生成本轮结果");
    }

    private void validateAction(RunContext execution) {
        var cp = execution.checkpoint();
        try {
            model.validateAction(cp.path("response").path("content").asText());
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
        enterFinal(execution);
    }

    private void executeTools(
            RunContext execution, InterviewRunStore.Row row, ExecutionTrace trace) {
        var c = execution.claim();
        var cp = execution.checkpoint();
        var policy = execution.budget().execution();
        var memorySelection = execution.memorySelection();
        var session = execution.session();
        var turns = execution.turns();
        var materials = execution.materials();
        var pending = execution.pending();
        var calls = cp.path("response").path("tool_calls");
        // 先校验整批工具请求，再执行其中任何一个；
        // 故障恢复时沿用该批已预占的预算。
        var callIds = new HashSet<String>();
        if (cp.path("fixedReserved").asBoolean()) callIds.add("fixed-search-1");
        for (var message : cp.path("messages"))
            if (message.path("role").asText().equals("tool"))
                callIds.add(message.path("tool_call_id").asText());
        int checked = 0;
        for (var call : calls) {
            tools.validate(
                    call.path("function").path("name").asText(), parseArgs(call), memorySelection);
            String callId = call.path("id").asText();
            if (checked++ < cp.path("toolIndex").asInt()) continue;
            if (callId.isBlank() || callId.length() > 160 || !callIds.add(callId))
                throw new RunFailureException("TOOL_CALL_ID");
        }
        if (policy.explicit()
                && cp.path("loop").path("steps").asInt()
                                + calls.size()
                                - cp.path("toolIndex").asInt()
                        > policy.maxSteps()) cp.withObject("loop").put("stopReason", "STEP_LIMIT");
        if (!cp.path("batchReserved").asBoolean()) {
            cp.put("batchReserved", true);
            store.batch(c, calls.size(), cp.toString());
        }
        for (int i = cp.path("toolIndex").asInt(); i < calls.size(); i++) {
            var call = calls.get(i);
            String name = call.path("function").path("name").asText(),
                    callId = call.path("id").asText();
            var args = InterviewRunService.canonical(parseArgs(call));
            var qualified = memory.read(c.owner(), memorySelection);
            // 将用户、固定输入和规范化参数纳入缓存键；仅复用本运行同一取证动作的回执。
            String key =
                    InterviewRunStore.sha(
                            "evidence-v1\n"
                                    + c.owner()
                                    + "\n"
                                    + row.inputHash()
                                    + "\n"
                                    + name
                                    + "\n"
                                    + args);
            var cached = store.cached(c, key);
            boolean skipped = policy.explicit() && cp.path("loop").has("stopReason");
            try (var toolSpan =
                    telemetry
                            .start(Stage.TOOL)
                            .tag("agent.run.id", c.id())
                            .tag("agent.attempt.id", trace.attemptId)
                            .tag("gen_ai.tool.name", name)
                            .tag("agent.tool.call_id_hash", InterviewRunStore.sha(callId))
                            .tag(
                                    "agent.tool.mode",
                                    skipped
                                            ? "SKIPPED"
                                            : cached.isPresent() ? "CACHE" : "EXECUTED")) {
                try {
                    var result =
                            skipped
                                    ? json.createObjectNode()
                                            .put("status", "SKIPPED")
                                            .put(
                                                    "reason",
                                                    cp.path("loop").path("stopReason").asText())
                                    : cached.isPresent()
                                            ? tools.checkedPayload(
                                                    name, json.readTree(cached.get()))
                                            : tools.execute(
                                                            session,
                                                            turns,
                                                            name,
                                                            args,
                                                            materials,
                                                            memorySelection,
                                                            qualified,
                                                            execution
                                                                    .budget()
                                                                    .toolAvailableMillis(
                                                                            cp.path("outputPhase")
                                                                                    .asText(
                                                                                            "LEGACY"),
                                                                            store.remainingMillis(
                                                                                    c.id())))
                                                    .payload();
                    // 回调可能在取消或来源撤销后完成，保存回执或复用模型前再次校验资格。
                    sourceGuard.require(c.owner(), session);
                    memory.read(c.owner(), memorySelection);
                    if (tools.requiresMemory(name)) {
                        var ids = new HashSet<String>();
                        for (var item : result.path("items")) {
                            if (item.has("id")) ids.add(item.path("id").asText());
                            for (var id : item.path("observationIds")) ids.add(id.asText());
                        }
                        var uses = cp.withArray("memoryUses");
                        int usedAt = pending == null ? 0 : pending.answeredCount();
                        for (var observed : qualified)
                            if (ids.contains(observed.id()))
                                uses.add(
                                        json.valueToTree(
                                                new MemoryReference(
                                                        observed.id(),
                                                        observed.revision(),
                                                        observed.sourceSessionId(),
                                                        observed.sourceVersion(),
                                                        observed.sourceRound(),
                                                        observed.competencyId(),
                                                        observed.dimension(),
                                                        observed.rubricVersion(),
                                                        observed.executionMode(),
                                                        usedAt)));
                    }
                    ((ArrayNode) cp.path("messages"))
                            .add(
                                    json.createObjectNode()
                                            .put("role", "tool")
                                            .put("tool_call_id", callId)
                                            .put("content", result.toString()));
                    if ("search_knowledge".equals(name) && result.has("references"))
                        ((ArrayNode) cp.path("sources"))
                                .addAll((ArrayNode) result.path("references"));
                    // 回执与下一工具索引一起落库，恢复时从未记录的工具继续。
                    cp.put("toolIndex", i + 1);
                    var observation =
                            policy.explicit() && !skipped
                                    ? AgentLoopState.observe(
                                            json,
                                            cp,
                                            name,
                                            args,
                                            result,
                                            tools.requiresMemory(name))
                                    : null;
                    store.receipt(
                            c,
                            key,
                            callId,
                            name,
                            args.toString(),
                            row.inputHash(),
                            result.toString(),
                            cp.toString(),
                            skipped ? "SKIPPED" : cached.isPresent() ? "CACHE" : "EXECUTED",
                            observation);
                    toolSpan.tag("agent.tool.receipt", "RECORDED");
                    // 工具调用 ID 来自供应商，先哈希再记录，避免任意正文进入日志。
                    log.info(
                            "run.tool_recorded runId={} attemptId={} epoch={} phase=TOOL"
                                + " toolCallIdHash={} outcome={}",
                            c.id(),
                            trace.attemptId,
                            c.epoch(),
                            InterviewRunStore.sha(callId),
                            skipped ? "SKIPPED" : cached.isPresent() ? "CACHE" : "EXECUTED");
                } catch (RuntimeException ex) {
                    toolSpan.failure(failureCode(ex));
                    throw ex;
                }
            }
        }
        cp.put("stage", "MODEL");
        store.checkpoint(c, cp.toString(), "MODEL", null, null);
    }

    /** 校验修正沿用同一检查点、来源和单次修正预算。 */
    private boolean validateAndCommit(RunContext execution, ExecutionTrace trace) {
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
                                String content = cp.path("response").path("content").asText();
                                if (cp.has("outputPhase")) content = model.finalContent(content);
                                var verified = verifiedReviewSources(execution);
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
                                                    + (cp.has("outputPhase")
                                                            ? InterviewDecisionContract.FINAL_REPAIR
                                                            : "")));
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

    /** 仅重试已回滚的数据库锁冲突；不重复模型调用，也不重试提交结果不明的连接故障。 */
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

    static String failureCode(Throwable failure) {
        if (failure instanceof InterviewRunCheckpoint.Invalid) return "RUN_CHECKPOINT_INVALID";
        if (failure instanceof MemoryRevokedException) return "RUN_MEMORY_REVOKED";
        if (failure instanceof SourceRevokedException) return "RUN_SOURCE_REVOKED";
        if (failure instanceof RunFailureException classified) return classified.code();
        if (failure instanceof UpstreamServiceException upstream && upstream.code() != null)
            return upstream.code();
        return "RUN_EXECUTION_FAILED";
    }

    static String modelFailureCode(Throwable failure) {
        String code = failureCode(failure);
        return code.startsWith("MODEL_") ? code : "MODEL_UNAVAILABLE";
    }

    private static long elapsedMillis(long started) {
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
        var level =
                Set.of(
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

    private static String location(Throwable failure) {
        var frames = failure.getStackTrace();
        if (frames.length == 0) return "unknown";
        var first = frames[0];
        return first.getClassName() + "." + first.getMethodName() + ":" + first.getLineNumber();
    }

    private JsonNode parseArgs(JsonNode call) {
        try {
            return json.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(call.path("function").path("arguments").asText());
        } catch (RuntimeException ex) {
            throw new RunFailureException("TOOL_ARGUMENTS");
        }
    }

    @PreDestroy
    public void close() {
        workers.shutdownNow();
        heartbeats.shutdownNow();
    }
}
