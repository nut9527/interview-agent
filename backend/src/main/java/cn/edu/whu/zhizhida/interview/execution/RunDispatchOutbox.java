package cn.edu.whu.zhizhida.interview.execution;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Optional;
import java.util.UUID;

/**
 * 管理任务通知的持久发送记录、发布租约和重试进度。
 *
 * <p>数据库先保存发送意图，发布器再与消息代理交互；发布令牌隔离不同领取批次的迟到确认。
 * 通知允许重复投递，任务执行资格由运行记录的租约决定。
 */
@Repository
public class RunDispatchOutbox {
    public record Delivery(RunDispatchMessage message, String token, int attempts) { }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public RunDispatchOutbox(JdbcTemplate jdbc, TransactionTemplate tx) { this.jdbc=jdbc; this.tx=tx; }

    /**
     * 领取到期的待发通知或发布租约已过期的通知，并为此次发布生成独立令牌。
     *
     * <p>仅锁定 Outbox 行，通过 SKIP LOCKED 支持并行领取；发送和等待代理确认在事务返回后执行。
     */
    public Optional<Delivery> claim() {
        return tx.execute(s -> {
            var rows=jdbc.query("SELECT o.* FROM interview_run_outbox o WHERE ("
                    + "(status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP(6)) OR "
                    + "(status='SENDING' AND lease_until<=CURRENT_TIMESTAMP(6))) "
                    + "AND EXISTS (SELECT 1 FROM interview_runs r WHERE r.id=o.run_id AND r.status IN ('ACCEPTED','RUNNING')) "
                    + "ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED", (r,n) -> new Delivery(
                            new RunDispatchMessage(1,r.getString("event_id"),"AGENT_RUN_READY",r.getString("run_id"),
                                    r.getLong("dispatch_seq"),r.getTimestamp("created_at").toInstant()),
                            UUID.randomUUID().toString(),r.getInt("attempt_count")+1));
            if(rows.isEmpty()) return Optional.empty();
            var delivery=rows.getFirst();
            jdbc.update("UPDATE interview_run_outbox SET status='SENDING',publisher_token=?,attempt_count=attempt_count+1,"
                    + "lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)) WHERE event_id=?",
                    delivery.token(),delivery.message().eventId());
            return Optional.of(delivery);
        });
    }
    public void published(Delivery delivery) {
        jdbc.update("UPDATE interview_run_outbox SET status='PUBLISHED',published_at=CURRENT_TIMESTAMP(6),"
                + "lease_until=NULL,last_error_code=NULL WHERE event_id=? AND publisher_token=? AND status='SENDING'",
                delivery.message().eventId(),delivery.token());
    }
    public void retry(Delivery delivery,String code) {
        int delay=Math.min(30,1 << Math.min(delivery.attempts(),5));
        jdbc.update("UPDATE interview_run_outbox SET status='PENDING',lease_until=NULL,last_error_code=?,"
                + "next_attempt_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)) "
                + "WHERE event_id=? AND publisher_token=? AND status='SENDING'",
                code,delay,delivery.message().eventId(),delivery.token());
    }
    public long pending() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM interview_run_outbox o JOIN interview_runs r ON r.id=o.run_id "
                + "WHERE o.status IN ('PENDING','SENDING') AND r.status IN ('ACCEPTED','RUNNING')",Long.class);
    }
    public double oldestPendingSeconds() {
        return jdbc.queryForObject("SELECT COALESCE(GREATEST(0,TIMESTAMPDIFF(MICROSECOND,MIN(o.created_at),"
                + "CURRENT_TIMESTAMP(6))/1000000.0),0) FROM interview_run_outbox o JOIN interview_runs r ON r.id=o.run_id "
                + "WHERE o.status IN ('PENDING','SENDING') AND r.status IN ('ACCEPTED','RUNNING')", Double.class);
    }
}
