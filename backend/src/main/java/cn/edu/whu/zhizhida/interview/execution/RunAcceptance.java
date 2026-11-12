package cn.edu.whu.zhizhida.interview.execution;

import static cn.edu.whu.zhizhida.interview.execution.InterviewRunStore.conflict;
import static cn.edu.whu.zhizhida.interview.execution.InterviewRunStore.sha;

import cn.edu.whu.zhizhida.common.ResourceNotFoundException;
import cn.edu.whu.zhizhida.common.execution.ExecutionPort.Command;
import cn.edu.whu.zhizhida.common.execution.ExecutionPort.Run;
import cn.edu.whu.zhizhida.common.execution.RunSubject;
import cn.edu.whu.zhizhida.interview.InterviewSessionRepository;
import cn.edu.whu.zhizhida.interview.api.InterviewSession;
import cn.edu.whu.zhizhida.interview.api.InterviewState;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunStore.Fixed;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunStore.Row;
import cn.edu.whu.zhizhida.learning.api.GoalSessionLinks;
import cn.edu.whu.zhizhida.learning.api.MissionBudget;
import cn.edu.whu.zhizhida.quota.AiRequestQuota;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** 命令去重、固定输入和预占额度共用受理事务。 */
final class RunAcceptance {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RunRepository rows;
    private final InterviewSessionRepository sessions;
    private final AiRequestQuota quota;
    private final ObjectMapper policyJson;
    private final RunAdmission admission;
    private final GoalSessionLinks goalSessions;
    private final ObjectProvider<MissionBudget> missionBudget;
    private final Function<String, RunSubject> subject;
    private final Consumer<String> accepted;
    private final RunJournal journal;

    RunAcceptance(
            JdbcTemplate jdbc,
            TransactionTemplate tx,
            RunRepository rows,
            InterviewSessionRepository sessions,
            AiRequestQuota quota,
            ObjectMapper json,
            RunAdmission admission,
            GoalSessionLinks goalSessions,
            ObjectProvider<MissionBudget> missionBudget,
            Function<String, RunSubject> subject,
            Consumer<String> accepted,
            RunJournal journal) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.rows = rows;
        this.sessions = sessions;
        this.quota = quota;
        this.policyJson = json;
        this.admission = admission;
        this.goalSessions = goalSessions;
        this.missionBudget = missionBudget;
        this.subject = subject;
        this.accepted = accepted;
        this.journal = journal;
    }

    Run accept(Command command) {
        return tx.execute(
                s -> {
                    var executor = subject.apply(command.subjectType());
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
                            rows.commandLocked(
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
                                + " VALUES(?,?,?,?,?,?,NULL,NULL,'TOOL','{\"policyVersion\":\"goal-plan-v2\"}',?,TIMESTAMPADD(SECOND,90,CURRENT_TIMESTAMP(6)),?,?,?,0,0,0)",
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
                            .reserve(command.owner(), command.subjectId(), id, false, 3, 3);
                    journal.event(id, "run.accepted", "CONTEXT", "目标规划已受理");
                    accepted.accept(id);
                    return read(command.owner(), id);
                });
    }

    /** 在同一事务内完成命令去重、输入固定与受理扣额；同键不同请求内容视为冲突。 */
    Row accept(
            long owner,
            String op,
            String key,
            String hash,
            String sessionId,
            int turn,
            Supplier<Fixed> freeze) {
        return tx.execute(
                s -> {
                    var existing = rows.command(owner, op, key);
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
                    existing = rows.commandLocked(owner, op, key);
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
                    Row row = rows.commandLocked(owner, op, key).orElseThrow();
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
                    accepted.accept(id);
                    return rows.get(owner, id);
                });
    }

    private Row same(Row row, String hash) {
        if (!row.requestHash().equals(hash)) throw conflict();
        return row;
    }

    private Run read(long owner, String id) {
        var row = rows.get(owner, id);
        return new Run(row.id(), row.status(), row.error());
    }
}
