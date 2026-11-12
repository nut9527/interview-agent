package cn.edu.whu.zhizhida.interview.execution;

import cn.edu.whu.zhizhida.common.execution.RunFailureException;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunStore.Claim;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunStore.Row;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Consumer;
import java.util.function.Function;

/** 领取、续租、过期回收与写入 fencing 共用同一套资格检查。 */
final class RunLease {
    private static final Logger log = LoggerFactory.getLogger(RunLease.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RunRepository rows;
    private final RunAdmission admission;
    private final RunAttemptLedger attempts;
    private final RunCommit completion;
    private final Consumer<Row> lockSubject;
    private final Consumer<Runnable> afterCommit;

    RunLease(
            JdbcTemplate jdbc,
            TransactionTemplate tx,
            RunRepository rows,
            RunAdmission admission,
            RunAttemptLedger attempts,
            RunCommit completion,
            Consumer<Row> lockSubject,
            Consumer<Runnable> afterCommit) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.rows = rows;
        this.admission = admission;
        this.attempts = attempts;
        this.completion = completion;
        this.lockSubject = lockSubject;
        this.afterCommit = afterCommit;
    }

    /** 接管待运行或租约过期的任务；递增代次使原执行者失去后续写入资格。 */
    Claim claim(Row candidate) {
        return tx.execute(
                s -> {
                    lockSubject.accept(candidate);
                    admission.lock();
                    Row row = rows.lock(candidate.owner(), candidate.id());
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
                    afterCommit.accept(
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
    void expireWaiting() {
        var expired = rows.expired();
        for (var known : expired)
            tx.executeWithoutResult(
                    s -> {
                        lockSubject.accept(known);
                        expireLocked(rows.lock(known.owner(), known.id()));
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
        completion.finish(
                row,
                "EXPIRED",
                reason.getFirst(),
                "run.expired",
                "RUN_QUEUE_TIMEOUT".equals(reason.getFirst()) ? "任务等待超时，请重新提交" : "运行已超时");
        return true;
    }

    /** 每次写入前校验租约代次、有效期、总截止时间与取消状态，并在同一事务内执行变更。 */
    <T> T fenced(Claim c, Function<Row, T> action) {
        return tx.execute(
                s -> {
                    lockSubject.accept(rows.get(c.owner(), c.id()));
                    Row row = rows.lock(c.owner(), c.id());
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
    boolean heartbeat(Claim c) {
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
}
