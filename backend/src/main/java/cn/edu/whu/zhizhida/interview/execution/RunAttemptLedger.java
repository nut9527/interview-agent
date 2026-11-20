package cn.edu.whu.zhizhida.interview.execution;

import java.util.UUID;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunStore.Claim;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunStore.Row;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunStore.ModelAttempt;
import cn.edu.whu.zhizhida.quota.AiRequestQuota;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 记录模型尝试的预算预占、响应和结果状态。
 *
 * <p>调用方持有运行记录锁并提供事务，额度预占与尝试序号一起落库；模型请求在事务外执行。
 */
final class RunAttemptLedger {
    private final JdbcTemplate jdbc;
    private final ObjectMapper policyJson;
    private final AiRequestQuota quota;
    private final RunJournal journal;

    RunAttemptLedger(JdbcTemplate jdbc, ObjectMapper policyJson, AiRequestQuota quota, RunJournal journal) {
        this.jdbc = jdbc;
        this.policyJson = policyJson;
        this.quota = quota;
        this.journal = journal;
    }

    /**
     * 按受理时固定的策略和当前检查点分配一次模型尝试，记录请求摘要与上下文规模。
     *
     * <p>分阶段协议为最终输出和最终修正分别保留名额；已有尝试持续计入预算，恢复执行时不清零。
     */
    ModelAttempt reserve(Row row, RunContextAssembler.Prepared prepared, long remaining) {
        var config = policyJson.readTree(row.config());
        var budget = BudgetProfile.frozen(policyJson, config);
        // 最终调用预留以数据库检查点为准，不信任调用方提供的阶段。
        var cp = InterviewRunCheckpoint.read(policyJson, row.checkpoint(), row.route());
        if (!cp.path("stage").asString().equals("MODEL"))
            throw new cn.edu.whu.zhizhida.common.execution.RunFailureException("RUN_CHECKPOINT_INVALID");
        String phase = cp.path("outputPhase").asString();
        boolean repaired = cp.path("repaired").asBoolean();
        if (repaired != (row.repairs() > 0) || phase.equals("ACTION") && repaired)
            throw new cn.edu.whu.zhizhida.common.execution.RunFailureException("RUN_CHECKPOINT_INVALID");
        if (row.attempts() >= budget.attemptLimit(phase, repaired)
                || remaining < AgentLoopPolicy.MIN_MODEL_BUDGET_MILLIS)
            throw new cn.edu.whu.zhizhida.common.execution.RunFailureException("RUN_BUDGET");
        long timeout = budget.callTimeout(phase, repaired, remaining);
        String recordedPhase = phase.equals("FINAL") && repaired ? "FINAL_REPAIR" : phase;
        if (phase.equals("FINAL") && jdbc.queryForObject(
                "SELECT COUNT(*) FROM interview_model_attempts WHERE run_id=? AND budget_phase=?",
                Integer.class, row.id(), recordedPhase) != 0)
            throw new cn.edu.whu.zhizhida.common.execution.RunFailureException("RUN_BUDGET");
        String id = UUID.randomUUID().toString();
        quota.reserveRunAttempt();
        jdbc.update(
                "INSERT INTO interview_model_attempts(attempt_id,run_id,ordinal,lease_epoch,status,request_hash,budget_phase,call_timeout_ms,context_version,input_chars,tool_chars,omitted_tool_results) VALUES(?,?,?,?,'STARTED',?,?,?,?,?,?,?)",
                id, row.id(), row.attempts() + 1, row.epoch(), prepared.requestHash(), recordedPhase, timeout,
                RunContextAssembler.VERSION, prepared.inputChars(),
                prepared.toolChars(), prepared.omittedResults());
        jdbc.update("UPDATE interview_runs SET model_attempts=model_attempts+1,phase='MODEL' WHERE id=?", row.id());
        journal.event(row.id(), "model.started", "MODEL", "正在分析回答");
        return new ModelAttempt(id, timeout);
    }

    String latestAttemptId(Claim claim) {
        return jdbc.query("SELECT a.attempt_id FROM interview_model_attempts a JOIN interview_runs r ON r.id=a.run_id "
                        + "WHERE r.id=? AND r.owner_id=? ORDER BY a.ordinal DESC LIMIT 1",
                (r, n) -> r.getString(1), claim.id(), claim.owner()).stream().findFirst().orElse("none");
    }

    /**
     * 记录当前租约代次的调用失败；网络故障或中断保留为 UNKNOWN，因为供应商可能已处理请求。
     */
    void fail(Claim c, String attempt, String code, long elapsed) {
        if (jdbc.update("UPDATE interview_model_attempts SET status=?,error_code=?,elapsed_ms=? "
                        + "WHERE attempt_id=? AND run_id=? AND lease_epoch=? AND status='STARTED'",
                code.startsWith("MODEL_NETWORK") || code.equals("MODEL_INTERRUPTED") ? "UNKNOWN" : "FAILED", code,
                elapsed, attempt, c.id(), c.epoch()) != 1)
            throw new cn.edu.whu.zhizhida.common.execution.RunFailureException("RUN_FENCED");
    }

    void succeed(Claim c, String attempt, String response, String usage, long elapsed) {
        if (jdbc.update(
                "UPDATE interview_model_attempts SET status='SUCCEEDED',response_json=?,usage_json=?,elapsed_ms=? "
                        + "WHERE attempt_id=? AND run_id=? AND lease_epoch=? AND status='STARTED'",
                response, usage, elapsed, attempt, c.id(), c.epoch()) != 1)
            throw new cn.edu.whu.zhizhida.common.execution.RunFailureException("RUN_FENCED");
    }

    /**
     * 在任务接管或终结时，将尚无结果的尝试标记为 UNKNOWN，保留已发生的预算占用。
     */
    void markInterrupted(String id) {
        jdbc.update("UPDATE interview_model_attempts SET status='UNKNOWN' WHERE run_id=? AND status='STARTED'", id);
    }
}
