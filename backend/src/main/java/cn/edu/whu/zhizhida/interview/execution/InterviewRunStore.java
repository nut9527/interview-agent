package cn.edu.whu.zhizhida.interview.execution;

import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.common.ResourceNotFoundException;
import cn.edu.whu.zhizhida.common.Sha256;
import cn.edu.whu.zhizhida.common.execution.ExecutionPort;
import cn.edu.whu.zhizhida.common.execution.RunFailureException;
import cn.edu.whu.zhizhida.common.execution.RunSubject;
import cn.edu.whu.zhizhida.interview.InterviewSessionRepository;
import cn.edu.whu.zhizhida.interview.api.InterviewRun;
import cn.edu.whu.zhizhida.interview.api.InterviewRun.InterviewRunTrace;
import cn.edu.whu.zhizhida.interview.api.InterviewSession;
import cn.edu.whu.zhizhida.interview.api.InterviewState;
import cn.edu.whu.zhizhida.interview.api.KnowledgeSnapshot;
import cn.edu.whu.zhizhida.interview.api.QuestionCatalog;
import cn.edu.whu.zhizhida.learning.api.GoalSessionLinks;
import cn.edu.whu.zhizhida.learning.api.MissionBudget;
import cn.edu.whu.zhizhida.learning.api.PracticeMemoryAccess;
import cn.edu.whu.zhizhida.observability.AgentTelemetry;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import cn.edu.whu.zhizhida.quota.AiRequestQuota;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 编排运行生命周期，并以短事务保存受理、领取和终态变更。
 *
 * <p>按需遵循业务主体、准入、运行记录、账本的加锁顺序；模型调用由 Worker 在事务外执行。
 * SQL 查询、训练关联、准入、日志与保留清理由各自组件负责。
 */
@Repository
public class InterviewRunStore implements ExecutionPort {
    private final QuestionCatalog questions;
    private static final Logger log = LoggerFactory.getLogger(InterviewRunStore.class);

    public record Row(
            String id,
            long owner,
            String operation,
            String requestId,
            String requestHash,
            String inputHash,
            String sessionId,
            int expectedTurn,
            String route,
            String config,
            String input,
            String checkpoint,
            String status,
            String phase,
            Instant deadline,
            long epoch,
            int attempts,
            int batches,
            int tools,
            int repairs,
            long seq,
            String error,
            String subjectType,
            String subjectId,
            long subjectRevision) {
        public boolean terminal() {
            return !status.equals("ACCEPTED") && !status.equals("RUNNING");
        }

        public InterviewRun view() {
            return new InterviewRun(
                    id,
                    status,
                    phase,
                    (status.equals("SUCCEEDED") || operation.equals("ANSWER")) ? sessionId : null,
                    expectedTurn,
                    error,
                    seq,
                    deadline,
                    "/api/interview-runs/" + id,
                    "/api/interview-runs/" + id + "/events");
        }
    }

    public record Claim(String id, long owner, String sessionId, long epoch) {}

    public record Fixed(String sessionId, String input, String config, String route) {}

    private final JdbcTemplate jdbc;
    private final RunRepository rows;
    private final RunRetention retention;
    private final TransactionTemplate tx;
    private final RunJournal journal;
    private final RunAttemptLedger attempts;
    private final RunToolReceipts receipts;
    private final RunTrainingTasks trainingTasks;
    private final GoalSessionLinks goalSessions;
    private final InterviewSessionRepository sessions;
    private final AiRequestQuota quota;
    private final ObjectMapper policyJson;
    private final BusinessMetrics metrics;
    private final RunAdmission admission;
    private final AgentTelemetry telemetry;
    private final PracticeMemoryAccess memory;
    private final Map<String, RunSubject> subjects;
    private final ObjectProvider<MissionBudget> missionBudget;

    public InterviewRunStore(
            JdbcTemplate jdbc,
            TransactionTemplate tx,
            InterviewSessionRepository sessions,
            AiRequestQuota quota,
            ObjectMapper json,
            BusinessMetrics metrics,
            List<RunSubject> subjects,
            RunAdmission admission,
            AgentTelemetry telemetry,
            GoalSessionLinks goalSessions,
            RunRetentionCoordinator retentionCoordinator,
            QuestionCatalog questions,
            PracticeMemoryAccess memory,
            ObjectProvider<MissionBudget> missionBudget) {
        this.questions = questions;
        this.memory = memory;
        this.missionBudget = missionBudget;
        this.jdbc = jdbc;
        this.rows = new RunRepository(jdbc);
        this.retention =
                new RunRetention(
                        jdbc, tx, rows, json, memory, this::lockSubject, retentionCoordinator);
        this.goalSessions = goalSessions;
        this.trainingTasks = new RunTrainingTasks(jdbc, goalSessions);
        this.journal = new RunJournal(jdbc, json);
        this.attempts = new RunAttemptLedger(jdbc, json, quota, journal);
        this.receipts = new RunToolReceipts(jdbc, json, journal);
        this.tx = tx;
        this.sessions = sessions;
        this.quota = quota;
        this.policyJson = json;
        this.metrics = metrics;
        this.admission = admission;
        this.telemetry = telemetry;
        var registered = new LinkedHashMap<String, RunSubject>();
        for (var subject : subjects) {
            if (subject.subjectType() == null || subject.subjectType().isBlank())
                throw new IllegalStateException("RUN_SUBJECT_TYPE_INVALID");
            if (registered.putIfAbsent(subject.subjectType(), subject) != null)
                throw new IllegalStateException("RUN_SUBJECT_DUPLICATE");
        }
        this.subjects = Map.copyOf(registered);
    }

    @Override
    public Run read(long owner, String id) {
        var row = get(owner, id);
        return new Run(row.id(), row.status(), row.error());
    }

    @Override
    public Run accept(Command command) {
        return tx.execute(
                s -> {
                    var executor = subject(command.subjectType());
                    executor.lock(command.owner(), command.subjectId());
                    admission.lock();
                    String hash =
                            sha(
                                    command.subjectType()
                                            + "\n"
                                            + command.subjectId()
                                            + "\n"
                                            + command.revision()
                                            + "\n"
                                            + command.input());
                    var existing =
                            commandLocked(
                                    command.owner(), command.operation(), command.requestId());
                    if (existing.isPresent()) {
                        same(existing.get(), hash);
                        return read(command.owner(), existing.get().id());
                    }
                    executor.validate(
                            command.owner(),
                            command.subjectId(),
                            command.revision(),
                            command.operation());
                    admission.requireAcceptance(command.owner());
                    String id = UUID.randomUUID().toString();
                    jdbc.update(
                            "INSERT INTO"
                                + " interview_runs(id,owner_id,operation,request_id,request_hash,input_hash,session_id,expected_turn,route,config_json,input_json,deadline,subject_type,subject_id,subject_revision,actual_tool_executions,tool_cache_hits,skipped_tool_requests)"
                                + " VALUES(?,?,?,?,?,?,NULL,NULL,'RULE','{\"policyVersion\":\"goal-plan-v1\"}',?,TIMESTAMPADD(SECOND,90,CURRENT_TIMESTAMP(6)),?,?,?,0,0,0)",
                            id,
                            command.owner(),
                            command.operation(),
                            command.requestId(),
                            hash,
                            sha(command.input()),
                            command.input(),
                            command.subjectType(),
                            command.subjectId(),
                            command.revision());
                    missionBudget
                            .getObject()
                            .reserve(command.owner(), command.subjectId(), id, false, 0, 0);
                    journal.event(id, "run.accepted", "CONTEXT", "目标规划已受理");
                    accepted(id);
                    return read(command.owner(), id);
                });
    }

    private RunSubject subject(String type) {
        var subject = subjects.get(type);
        if (subject == null) throw new RunFailureException("RUN_SUBJECT_UNSUPPORTED");
        return subject;
    }

    /**
     * 在租约校验通过后，将业务主体变更与运行成功状态提交到同一事务。
     *
     * <p>回调中的持久化操作需加入当前事务，避免业务已保存但任务终态回滚。
     */
    @Override
    public void complete(Context context, Runnable mutation) {
        var known = get(context.owner(), context.id());
        fenced(
                new Claim(context.id(), context.owner(), known.sessionId(), context.epoch()),
                row -> {
                    mutation.run();
                    finish(row, "SUCCEEDED", null, "subject.committed", "目标规划草稿已保存，等待本人确认");
                    return null;
                });
    }

    public Row get(long owner, String id) {
        return rows.get(owner, id);
    }

    public List<Row> active(long owner) {
        return rows.active(owner);
    }

    public Optional<Row> command(long owner, String op, String key) {
        return rows.command(owner, op, key);
    }

    private Optional<Row> commandLocked(long owner, String op, String key) {
        return rows.commandLocked(owner, op, key);
    }

    /** 在同一事务内完成命令去重、输入固定与受理扣额；同键不同请求内容视为冲突。 */
    public Row accept(
            long owner,
            String op,
            String key,
            String hash,
            String sessionId,
            int turn,
            Supplier<Fixed> freeze) {
        return tx.execute(
                s -> {
                    var existing = command(owner, op, key);
                    if (existing.isPresent()) return same(existing.get(), hash);
                    var goal =
                            op.equals("ANSWER")
                                    ? goalSessions.goalForSession(owner, sessionId)
                                    : Optional.<String>empty();
                    // 目标命令和训练启动已在准入前锁定目标。
                    // 下方预占不得以反向锁序再次取得同一目标。
                    goal.ifPresent(id -> missionBudget.getObject().lock(owner, id));
                    InterviewSession lockedSession = null;
                    if (sessionId != null) {
                        lockedSession =
                                sessions.findByIdForUpdate(owner, sessionId)
                                        .orElseThrow(
                                                () ->
                                                        new ResourceNotFoundException(
                                                                "interview session not found"));
                    }
                    // 快照准备可能锁定历史来源，须在共享准入互斥锁之前完成。
                    // 避免持有共享准入锁等待来源，阻塞其他用户。
                    Fixed fixed = freeze.get();
                    var budget =
                            BudgetProfile.frozen(policyJson, policyJson.readTree(fixed.config()));
                    admission.lock();
                    existing = commandLocked(owner, op, key);
                    if (existing.isPresent()) return same(existing.get(), hash);
                    if (lockedSession != null
                            && (lockedSession.state() != InterviewState.WAITING_ANSWER
                                    || lockedSession.turnCount() != turn)) throw conflict();
                    if (lockedSession != null
                            && jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM interview_sessions WHERE"
                                                + " user_id=? AND session_id=? AND (active_run_id"
                                                + " IS NOT NULL OR"
                                                + " answer_lease_until>CURRENT_TIMESTAMP(6))",
                                            Integer.class,
                                            owner,
                                            sessionId)
                                    != 0) throw conflict();
                    admission.requireAcceptance(owner);
                    String id = UUID.randomUUID().toString();
                    // 唯一约束与锁内重放检查共同保护并发启动，只有新命令扣减受理额度。
                    jdbc.update(
                            "INSERT INTO"
                                + " interview_runs(id,owner_id,operation,request_id,request_hash,input_hash,session_id,expected_turn,route,config_json,input_json,deadline,actual_tool_executions,tool_cache_hits,skipped_tool_requests)"
                                + " VALUES(?,?,?,?,?,'',?,?,'PENDING','{}','{}',TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6)),0,0,0)"
                                + " ON DUPLICATE KEY UPDATE id=id",
                            id,
                            owner,
                            op,
                            key,
                            hash,
                            sessionId == null ? id : sessionId,
                            turn,
                            budget.execution().deadlineMillis() * 1_000);
                    Row row = commandLocked(owner, op, key).orElseThrow();
                    if (!row.id().equals(id)) return same(row, hash);
                    if (sessionId != null
                            && jdbc.update(
                                            "UPDATE interview_sessions SET active_run_id=? WHERE"
                                                + " session_id=? AND user_id=? AND active_run_id IS"
                                                + " NULL AND (answer_lease_until IS NULL OR"
                                                + " answer_lease_until<=CURRENT_TIMESTAMP(6))",
                                            id,
                                            sessionId,
                                            owner)
                                    != 1) throw conflict();
                    quota.admitRun(
                            Long.toString(owner),
                            op.equals("START")
                                    ? AiRequestQuota.INTERVIEW_START_UNITS
                                    : AiRequestQuota.INTERVIEW_REVIEW_UNITS);
                    jdbc.update(
                            "UPDATE interview_runs SET"
                                + " input_json=?,input_hash=?,config_json=?,route=?,session_id=?,subject_id=?"
                                + " WHERE id=?",
                            fixed.input(),
                            sha(fixed.input() + "\n" + fixed.config() + "\n" + fixed.route()),
                            fixed.config(),
                            fixed.route(),
                            fixed.sessionId(),
                            fixed.sessionId(),
                            id);
                    if (goal.isPresent())
                        missionBudget
                                .getObject()
                                .reserve(
                                        owner,
                                        goal.get(),
                                        id,
                                        false,
                                        budget.execution().maxModelAttempts(),
                                        budget.execution().maxToolExecutions());
                    journal.event(id, "run.accepted", "CONTEXT", "请求已受理");
                    accepted(id);
                    return get(owner, id);
                });
    }

    private Row same(Row row, String hash) {
        if (!row.requestHash().equals(hash)) throw conflict();
        return row;
    }

    public List<Row> candidates() {
        return rows.candidates();
    }

    public Optional<Row> dispatchCandidate(String id) {
        return rows.dispatchCandidate(id);
    }

    public void remindSoon(String id) {
        rows.remindSoon(id);
    }

    /** 恢复器只创建有持久间隔的提醒，不改变运行输入、截止时间或执行预算。 */
    public void recoverDispatches() {
        expireWaiting();
        var recoverable = rows.recoverable();
        for (var known : recoverable) {
            if (remainingMillis(known.id()) <= 0) {
                var claim = claim(known);
                if (claim != null) fail(claim, "RUN_BUDGET");
                continue;
            }
            tx.executeWithoutResult(
                    s -> {
                        lockSubject(known);
                        var row = lock(known.owner(), known.id());
                        if (row.terminal()) return;
                        int changed =
                                jdbc.update(
                                        "UPDATE interview_runs SET"
                                            + " dispatch_seq=dispatch_seq+1,next_dispatch_at=TIMESTAMPADD(SECOND,15,CURRENT_TIMESTAMP(6))"
                                            + " WHERE id=? AND"
                                            + " next_dispatch_at<=CURRENT_TIMESTAMP(6) AND"
                                            + " deadline>CURRENT_TIMESTAMP(6) AND (not_before IS"
                                            + " NULL OR not_before<=CURRENT_TIMESTAMP(6)) AND "
                                                + RunRepository.CLAIMABLE
                                                + " AND NOT EXISTS (SELECT 1 FROM"
                                                + " interview_run_outbox o WHERE o.run_id=? AND"
                                                + " o.status IN ('PENDING','SENDING'))",
                                        row.id(),
                                        row.id());
                        if (changed == 1) enqueue(row.id());
                    });
        }
    }

    public List<Row> forSession(long owner, String session) {
        return rows.forSession(owner, session);
    }

    /** 接管待运行或租约过期的任务；递增代次使原执行者失去后续写入资格。 */
    public Claim claim(Row candidate) {
        return tx.execute(
                s -> {
                    lockSubject(candidate);
                    admission.lock();
                    Row row = lock(candidate.owner(), candidate.id());
                    if (row.terminal()) return null;
                    if (expireLocked(row)) return null;
                    if (!admission.canClaim(row)) return null;
                    int changed =
                            jdbc.update(
                                    "UPDATE interview_runs SET"
                                        + " status='RUNNING',not_before=NULL,first_started_at=COALESCE(first_started_at,CURRENT_TIMESTAMP(6)),lease_epoch=lease_epoch+1,lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6))"
                                        + " WHERE id=? AND "
                                            + RunRepository.CLAIMABLE
                                            + " AND "
                                            + RunRepository.READY,
                                    row.id());
                    if (changed != 1) return null;
                    // 中断前发出的请求可能已被供应商处理，只能标记结果未知，不能当作未调用。
                    attempts.markInterrupted(row.id());
                    long epoch = row.epoch() + 1;
                    afterCommit(
                            () ->
                                    log.info(
                                            "runId={} epoch={} phase={} event=run.claimed",
                                            row.id(),
                                            epoch,
                                            row.phase()));
                    return new Claim(row.id(), row.owner(), row.sessionId(), epoch);
                });
    }

    /** 过期回收不要求执行许可，容量饱和不能阻止到期处理或互斥锁释放。 */
    public void expireWaiting() {
        var expired = rows.expired();
        for (var known : expired)
            tx.executeWithoutResult(
                    s -> {
                        lockSubject(known);
                        expireLocked(lock(known.owner(), known.id()));
                    });
    }

    private boolean expireLocked(Row row) {
        if (row.terminal()) return false;
        var reason =
                jdbc.queryForList(
                        "SELECT IF(deadline<=CURRENT_TIMESTAMP(6),'RUN_BUDGET','RUN_QUEUE_TIMEOUT')"
                            + " FROM interview_runs WHERE id=? AND "
                                + RunRepository.CLAIMABLE
                                + " AND "
                                + RunRepository.EXPIRED,
                        String.class,
                        row.id());
        if (reason.isEmpty()) return false;
        finish(
                row,
                "EXPIRED",
                reason.getFirst(),
                "run.expired",
                "RUN_QUEUE_TIMEOUT".equals(reason.getFirst()) ? "任务等待超时，请重新提交" : "运行已超时");
        return true;
    }

    private void lockSession(long owner, String id) {
        if (id != null) sessions.findByIdForUpdate(owner, id);
    }

    private void lockSubject(Row row) {
        if (row.subjectType().equals("INTERVIEW")) {
            // 未完成的 START 尚无会话；锁定不存在的键会取得 InnoDB 间隙锁，使并发开场插入可能死锁。
            // 创建资格由 Run 行锁与 epoch 校验保护，会话插入与运行终态在 commit() 中原子提交。
            if (!row.operation().equals("START") || row.terminal())
                lockSession(row.owner(), row.sessionId());
        } else subject(row.subjectType()).lock(row.owner(), row.subjectId());
    }

    private Row lock(long owner, String id) {
        return rows.lock(owner, id);
    }

    /** 每次写入前校验租约代次、有效期、总截止时间与取消状态，并在同一事务内执行变更。 */
    public <T> T fenced(Claim c, Function<Row, T> action) {
        return tx.execute(
                s -> {
                    lockSubject(get(c.owner(), c.id()));
                    Row row = lock(c.owner(), c.id());
                    Integer valid =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM interview_runs WHERE id=? AND"
                                        + " status='RUNNING' AND lease_epoch=? AND"
                                        + " lease_until>CURRENT_TIMESTAMP(6) AND"
                                        + " deadline>CURRENT_TIMESTAMP(6) AND"
                                        + " cancel_requested=FALSE",
                                    Integer.class,
                                    c.id(),
                                    c.epoch());
                    if (valid == null || valid != 1) throw new RunFailureException("RUN_FENCED");
                    return action.apply(row);
                });
    }

    /**
     * 续租仍有效的执行代次，过期租约或已取消任务不能通过心跳恢复执行权。
     *
     * <p>续租只延长执行租约，任务受理时确定的总截止时间保持不变。
     */
    public boolean heartbeat(Claim c) {
        return jdbc.update(
                        "UPDATE interview_runs SET"
                            + " lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)) WHERE id=?"
                            + " AND owner_id=? AND lease_epoch=? AND status='RUNNING' AND"
                            + " cancel_requested=FALSE AND lease_until>CURRENT_TIMESTAMP(6) AND"
                            + " deadline>CURRENT_TIMESTAMP(6)",
                        c.id(),
                        c.owner(),
                        c.epoch())
                == 1;
    }

    public void checkpoint(Claim c, String json, String phase, String type, String message) {
        fenced(
                c,
                row -> {
                    journal.checkpoint(row.id(), json, phase);
                    if (type != null) journal.event(row.id(), type, phase, message);
                    return null;
                });
    }

    public record ModelAttempt(String id, long timeoutMillis) {}

    /** 发起外部请求前保存出站投影并预占额度。 */
    public ModelAttempt reserveModelAttempt(Claim c, RunContextAssembler.Prepared prepared) {
        return fenced(c, row -> attempts.reserve(row, prepared, remainingMillis(row.id())));
    }

    public long remainingMillis(String id) {
        return rows.remainingMillis(id);
    }

    /** 恢复后的诊断关联复用 attempt 账本，不改变历史检查点协议。 */
    public String latestAttemptId(Claim claim) {
        return attempts.latestAttemptId(claim);
    }

    public void attemptFailure(Claim c, String attempt, String code, long elapsed) {
        fenced(
                c,
                row -> {
                    attempts.fail(c, attempt, code, elapsed);
                    return null;
                });
    }

    /** 失败、检查点、释放和后续领取资格在同一事务提交，不休眠占用线程。 */
    public boolean deferModelFailure(
            Claim c, String attempt, String code, long elapsed, String checkpoint) {
        if (!ModelRetryPolicy.retryable(code))
            throw new IllegalArgumentException("Non-retryable model failure");
        return fenced(
                c,
                row -> {
                    attempts.fail(c, attempt, code, elapsed);
                    var policy = loopPolicy(row);
                    long delay = ModelRetryPolicy.delayMillis(row.id(), row.attempts());
                    // 分阶段协议恢复后仍须保留最终调用的修正预算。
                    long minimum =
                            AgentLoopPolicy.MIN_MODEL_BUDGET_MILLIS
                                    + (policyJson.readTree(checkpoint).has("outputPhase")
                                            ? policy.callMillis()
                                            : 0);
                    if (row.attempts() >= policy.maxModelAttempts()
                            || remainingMillis(row.id()) <= delay + minimum) return false;
                    journal.checkpoint(row.id(), checkpoint, "RETRY_WAIT");
                    jdbc.update(
                            "UPDATE interview_runs SET"
                                + " status='ACCEPTED',lease_epoch=lease_epoch+1,lease_until=NULL,"
                                + "not_before=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6)),next_dispatch_at=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6))"
                                + " WHERE id=?",
                            delay * 1_000,
                            delay * 1_000,
                            row.id());
                    journal.event(
                            row.id(), "model.retry_scheduled", "RETRY_WAIT", "模型服务暂时不可用，稍后自动重试");
                    // 提醒由已有 Outbox 和恢复扫描负责，不新增调度器、命令或额度。
                    afterCommit(
                            () ->
                                    log.info(
                                            "run.retry_scheduled runId={} attemptId={} epoch={}"
                                                + " delayMs={} code={}",
                                            row.id(),
                                            attempt,
                                            c.epoch(),
                                            delay,
                                            code));
                    return true;
                });
    }

    /** MQ 仅能确认已提交的释放，不能确认任意未终结结果。 */
    public boolean retryScheduled(Claim c) {
        return Integer.valueOf(1)
                .equals(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM interview_runs WHERE id=? AND owner_id=? AND"
                                    + " status='ACCEPTED' AND phase='RETRY_WAIT' AND not_before IS"
                                    + " NOT NULL AND lease_until IS NULL AND lease_epoch>?",
                                Integer.class,
                                c.id(),
                                c.owner(),
                                c.epoch()));
    }

    public void response(
            Claim c,
            String attempt,
            String response,
            String usage,
            long elapsed,
            String checkpoint,
            String phase) {
        fenced(
                c,
                row -> {
                    attempts.succeed(c, attempt, response, usage, elapsed);
                    journal.checkpoint(row.id(), checkpoint, phase);
                    return null;
                });
    }

    public void repair(Claim c, String checkpoint) {
        fenced(
                c,
                row -> {
                    var policy = loopPolicy(row);
                    if (row.repairs() >= policy.maxRepairs()
                            || row.attempts() >= policy.maxModelAttempts())
                        throw new RunFailureException("RUN_REPAIR_LIMIT");
                    jdbc.update("UPDATE interview_runs SET repairs=repairs+1 WHERE id=?", row.id());
                    journal.checkpoint(row.id(), checkpoint, "MODEL");
                    return null;
                });
    }

    public void batch(Claim c, int count, String checkpoint) {
        fenced(
                c,
                row -> {
                    var policy = loopPolicy(row);
                    if (count < 1
                            || row.batches() >= policy.maxToolBatches()
                            || count > policy.maxToolExecutions() - row.tools()
                            || row.attempts() >= policy.maxModelAttempts()
                            || remainingMillis(row.id()) < AgentLoopPolicy.MIN_TOOL_BUDGET_MILLIS)
                        throw new RunFailureException("RUN_TOOL_BUDGET");
                    jdbc.update(
                            "UPDATE interview_runs SET"
                                + " tool_batches=tool_batches+1,tool_executions=tool_executions+?"
                                + " WHERE id=?",
                            count,
                            row.id());
                    journal.checkpoint(row.id(), checkpoint, "TOOL");
                    journal.event(row.id(), "tool.started", "TOOL", "正在核对资料");
                    return null;
                });
    }

    private AgentLoopPolicy loopPolicy(Row row) {
        // 与 Worker 一样使用受理时保存的策略，运行中的任务不随默认配置变化。
        return BudgetProfile.frozen(policyJson, policyJson.readTree(row.config())).execution();
    }

    public Optional<String> cached(Claim c, String key) {
        return fenced(c, row -> receipts.cached(row.id(), key));
    }

    /** 与下一检查点同事务保存的本轮检索回执；不读取来自模型消息或其他工具的引用。 */
    public List<KnowledgeSnapshot.Reference> knowledgeReceipts(Claim claim) {
        return fenced(claim, row -> receipts.knowledgeReceipts(row.id()));
    }

    public void receipt(
            Claim c,
            String key,
            String callId,
            String name,
            String args,
            String source,
            String result,
            String checkpoint) {
        receipt(c, key, callId, name, args, source, result, checkpoint, false, null);
    }

    public void receipt(
            Claim c,
            String key,
            String callId,
            String name,
            String args,
            String source,
            String result,
            String checkpoint,
            boolean cacheHit,
            AgentLoopState.Observation observation) {
        receipt(
                c,
                key,
                callId,
                name,
                args,
                source,
                result,
                checkpoint,
                cacheHit ? "CACHE" : "EXECUTED",
                observation);
    }

    public void receipt(
            Claim c,
            String key,
            String callId,
            String name,
            String args,
            String source,
            String result,
            String checkpoint,
            String execution,
            AgentLoopState.Observation observation) {
        fenced(
                c,
                row -> {
                    receipts.save(
                            row,
                            key,
                            callId,
                            name,
                            args,
                            source,
                            result,
                            checkpoint,
                            execution,
                            observation);
                    return null;
                });
    }

    /** 会话写入、训练任务关联与运行成功状态共用事务，任何条件冲突都整体回滚。 */
    public void commit(Claim c, InterviewSession candidate, boolean opening) {
        fenced(
                c,
                row -> {
                    if (opening
                            && candidate.agent() != null
                            && candidate.agent().training() != null
                            && candidate.agent().training().question() != null) {
                        var question = candidate.agent().training().question();
                        Integer expectedVersion =
                                candidate.agent().training().retry() == null
                                        ? question.version()
                                        : null;
                        questions.lockPublishedForExecution(
                                c.owner(), question.id(), expectedVersion);
                    }
                    var mapper = policyJson;
                    var input = mapper.readTree(row.input());
                    var selection =
                            input.has("practiceMemory")
                                    ? mapper.treeToValue(
                                            input.path("practiceMemory"),
                                            PracticeMemoryAccess.Selection.class)
                                    : PracticeMemoryAccess.Selection.none();
                    if (selection.available()) memory.lockForCommit(c.owner(), selection);
                    if (!opening) {
                        int bound =
                                jdbc.update(
                                        "UPDATE interview_sessions SET active_run_id=NULL WHERE"
                                            + " user_id=? AND session_id=? AND active_run_id=? AND"
                                            + " turn_count=? AND state='WAITING_ANSWER'",
                                        c.owner(),
                                        c.sessionId(),
                                        c.id(),
                                        row.expectedTurn());
                        if (bound != 1) throw conflict();
                    }
                    sessions.save(c.owner(), candidate);
                    if (opening) {
                        var archive = input.path("materials");
                        jdbc.update(
                                "UPDATE interview_sessions SET material_manifest_json=? WHERE"
                                    + " session_id=? AND user_id=?",
                                archive.toString(),
                                candidate.sessionId(),
                                c.owner());
                    }
                    trainingTasks.committed(c.owner(), row.id(), candidate.sessionId());
                    finish(row, "SUCCEEDED", null, "turn.committed", "本轮结果已保存");
                    if (opening)
                        jdbc.update(
                                "UPDATE interview_runs SET input_json=? WHERE id=?",
                                "{\"sessionId\":\"" + candidate.sessionId() + "\"}",
                                row.id());
                    return null;
                });
    }

    public Row cancel(long owner, String id) {
        Row known = get(owner, id);
        return tx.execute(
                s -> {
                    lockSubject(known);
                    Row row = lock(owner, id);
                    if (!row.terminal()) {
                        jdbc.update(
                                "UPDATE interview_runs SET cancel_requested=TRUE WHERE id=?", id);
                        finish(row, "CANCELLED", null, "run.cancelled", "运行已取消");
                    }
                    return get(owner, id);
                });
    }

    public void fail(Claim c, String code) {
        tx.executeWithoutResult(
                s -> {
                    var knownRows = rows.find(c.owner(), c.id(), false);
                    if (knownRows.isEmpty()) return;
                    var known = knownRows.getFirst();
                    lockSubject(known);
                    var lockedRows = rows.find(c.owner(), c.id(), true);
                    if (lockedRows.isEmpty()) return;
                    Row row = lockedRows.getFirst();
                    if (!row.terminal()
                            && row.epoch() == c.epoch()
                            && Boolean.TRUE.equals(
                                    jdbc.queryForObject(
                                            "SELECT lease_until>CURRENT_TIMESTAMP(6) FROM"
                                                + " interview_runs WHERE id=?",
                                            Boolean.class,
                                            row.id()))) {
                        boolean expired = remainingMillis(row.id()) <= 0;
                        finish(
                                row,
                                expired ? "EXPIRED" : "FAILED",
                                code,
                                expired ? "run.expired" : "run.failed",
                                expired ? "运行已超时" : "运行未完成，请检查后重新提交");
                        if ("RUN_SOURCE_REVOKED".equals(code) || "RUN_MEMORY_REVOKED".equals(code))
                            retention.clearPrivate(row.id());
                    }
                });
    }

    /**
     * 在调用方已加锁的事务中，统一写入终态、最终事件并解除会话占用。
     *
     * <p>递增租约代次使迟到结果失去写入资格；监控计数和日志在事务提交后记录。
     */
    private void finish(Row row, String status, String code, String type, String message) {
        if (!status.equals("SUCCEEDED")) trainingTasks.failed(row.owner(), row.id());
        int finished =
                jdbc.update(
                        "UPDATE interview_runs SET"
                            + " status=?,error_code=?,phase=IF(?='SUCCEEDED','COMMIT',phase),lease_epoch=lease_epoch+1,lease_until=NULL,not_before=NULL,updated_at=CURRENT_TIMESTAMP(6)"
                            + " WHERE id=? AND (?<>'SUCCEEDED' OR (status='RUNNING' AND"
                            + " lease_epoch=? AND lease_until>CURRENT_TIMESTAMP(6) AND"
                            + " deadline>CURRENT_TIMESTAMP(6) AND cancel_requested=FALSE))",
                        status,
                        code,
                        status,
                        row.id(),
                        status,
                        row.epoch());
        if (finished != 1) throw new RunFailureException("RUN_FENCED");
        if (status.equals("SUCCEEDED"))
            journal.addStep(
                    row,
                    "PROPOSE",
                    null,
                    "VALIDATED",
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    null);
        String reason = code;
        if (reason == null && row.checkpoint() != null)
            reason =
                    policyJson
                            .readTree(row.checkpoint())
                            .path("loop")
                            .path("stopReason")
                            .asText(null);
        if (reason == null) reason = status.equals("SUCCEEDED") ? "COMPLETED" : status;
        journal.addStep(row, "STOP", null, status, 0, null, null, null, null, null, false, reason);
        attempts.markInterrupted(row.id());
        jdbc.update(
                "UPDATE interview_sessions SET active_run_id=NULL WHERE user_id=? AND session_id=?"
                    + " AND active_run_id=?",
                row.owner(),
                row.sessionId(),
                row.id());
        journal.event(row.id(), type, status.equals("SUCCEEDED") ? "COMMIT" : row.phase(), message);
        // 使用数据库时间包含排队及恢复耗时；只有事务真正提交后才计一次终态。
        Long elapsed =
                jdbc.queryForObject(
                        "SELECT TIMESTAMPDIFF(MICROSECOND,created_at,CURRENT_TIMESTAMP(6)) DIV 1000"
                            + " FROM interview_runs WHERE id=?",
                        Long.class,
                        row.id());
        var outcome = BusinessMetrics.RunOutcome.valueOf(status);
        afterCommit(
                () -> {
                    metrics.runFinished(outcome, elapsed == null ? 0 : elapsed);
                    log.info(
                            "runId={} epoch={} phase={} event=run.finished status={} code={}"
                                + " elapsedMs={}",
                            row.id(),
                            row.epoch(),
                            status.equals("SUCCEEDED") ? "COMMIT" : row.phase(),
                            status,
                            code,
                            elapsed);
                });
    }

    private void accepted(String id) {
        // 与受理同事务保存；幂等命令重放不得替换父追踪上下文。
        try (var span = telemetry.start(AgentTelemetry.Stage.ACCEPT).tag("agent.run.id", id)) {
            var parent = span.parent();
            if (parent != null)
                jdbc.update(
                        "UPDATE interview_runs SET"
                            + " trace_id=?,trace_parent_span_id=?,trace_sampled=? WHERE id=?",
                        parent.traceId(),
                        parent.spanId(),
                        parent.sampled(),
                        id);
        }
        admission.queued(id);
        enqueue(id);
        jdbc.update(
                "UPDATE interview_runs SET"
                    + " next_dispatch_at=TIMESTAMPADD(SECOND,15,CURRENT_TIMESTAMP(6)) WHERE id=?",
                id);
        afterCommit(() -> log.info("runId={} epoch=0 phase=CONTEXT event=run.accepted", id));
    }

    public AgentTelemetry.Parent traceParent(Claim claim) {
        return journal.traceParent(claim);
    }

    private void enqueue(String id) {
        jdbc.update(
                "INSERT INTO interview_run_outbox(event_id,run_id,dispatch_seq) "
                        + "SELECT ?,id,dispatch_seq FROM interview_runs WHERE id=?",
                UUID.randomUUID().toString(),
                id);
    }

    private void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        // 监控故障不得将已经提交的业务结果伪装成失败；不输出可能含正文的异常消息。
                        try {
                            action.run();
                        } catch (RuntimeException ex) {
                            log.warn(
                                    "event=run.telemetry_failed exceptionType={}",
                                    ex.getClass().getName());
                        }
                    }
                });
    }

    public InterviewRunTrace trace(long owner, String id, boolean hidden) {
        return journal.trace(get(owner, id), hidden);
    }

    public List<InterviewRun.Event> events(long owner, String id, long after) {
        return journal.events(get(owner, id), after);
    }

    public String materials(long owner, String session) {
        return rows.materials(owner, session);
    }

    public void deleteSession(long owner, String session) {
        rows.deleteSession(owner, session);
    }

    public void deleteOwned(long owner) {
        rows.deleteOwned(owner);
    }

    public void prune() {
        retention.prune();
    }

    public static String sha(String input) {
        return Sha256.hex(input);
    }

    private static BusinessException conflict() {
        return new BusinessException(HttpStatus.CONFLICT, "面试命令或回合已变更，请核对原运行结果。");
    }
}
