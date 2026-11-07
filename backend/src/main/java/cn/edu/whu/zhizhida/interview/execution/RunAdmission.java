package cn.edu.whu.zhizhida.interview.execution;

import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.common.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 数据库为排队与执行资格的事实源；仅在主体锁之后的短事务内使用。 */
@Component
public class RunAdmission {
    private final JdbcTemplate jdbc;
    private final int maxActive;
    private final int maxOutstanding;
    private final int queueSeconds;

    public RunAdmission(JdbcTemplate jdbc,
            @Value("${interview.runs.capacity.max-active:4}") int maxActive,
            @Value("${interview.runs.capacity.max-outstanding:20}") int maxOutstanding,
            @Value("${interview.runs.capacity.queue-seconds:10}") int queueSeconds) {
        if (maxActive < 1 || maxActive > 64 || maxOutstanding < maxActive || maxOutstanding > 1000
                || queueSeconds < 1 || queueSeconds > 90)
            throw new IllegalArgumentException("Invalid run capacity configuration");
        this.jdbc = jdbc;
        this.maxActive = maxActive;
        this.maxOutstanding = maxOutstanding;
        this.queueSeconds = queueSeconds;
    }

    public void lock() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Run admission requires a transaction");
        jdbc.queryForObject("SELECT id FROM agent_run_admission WHERE id=1 FOR UPDATE", Integer.class);
    }

    public void requireAcceptance(long owner) {
        // 锁定读可见并发提交，即使调用方已经建立可重复读快照。
        var pending = jdbc.queryForList("SELECT owner_id,first_started_at FROM interview_runs "
                + "WHERE status IN ('ACCEPTED','RUNNING') FOR UPDATE");
        long own = pending.stream().filter(r -> ((Number) r.get("owner_id")).longValue() == owner).count();
        boolean waiting = pending.stream().anyMatch(r -> ((Number) r.get("owner_id")).longValue() == owner
                && r.get("first_started_at") == null);
        if (waiting || own >= 2)
            throw new BusinessException(ErrorCode.RUN_USER_CAPACITY, "已有等待中的任务，请等待执行或取消后重试。");
        // 容量包含执行和退避中的命令，重试不得突破队列上限。
        if (pending.size() >= maxOutstanding)
            throw new BusinessException(ErrorCode.RUN_QUEUE_FULL, "任务队列已满，请稍后重试。");
    }

    public boolean canClaim(InterviewRunStore.Row row) {
        var active = jdbc.queryForList("SELECT id,owner_id,first_started_at,"
                + "(status='RUNNING' AND lease_until>CURRENT_TIMESTAMP(6)) AS live "
                + "FROM interview_runs WHERE status IN ('ACCEPTED','RUNNING') FOR UPDATE");
        int live = 0;
        for (var other : active) {
            if (other.get("id").equals(row.id())) continue;
            if (((Number) other.get("live")).intValue() != 0) live++;
            // 退避保留用户的在途命令，但不占执行名额。
            if (((Number) other.get("owner_id")).longValue() == row.owner()
                    && other.get("first_started_at") != null) return false;
        }
        return live < maxActive;
    }

    public void queued(String id) {
        jdbc.update("UPDATE interview_runs SET queue_deadline=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)) WHERE id=?",
                queueSeconds, id);
    }
}
