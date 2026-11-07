package cn.edu.whu.zhizhida.interview.execution;

import cn.edu.whu.zhizhida.common.MemoryRevokedException;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunStore.Row;
import cn.edu.whu.zhizhida.learning.api.PracticeMemoryAccess;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.util.function.Consumer;

/** 私有执行快照的保留与撤销清理；保留命令去重键和公开事件，不允许再次执行已清理命令。 */
final class RunRetention {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RunRepository rows;
    private final ObjectMapper policyJson;
    private final PracticeMemoryAccess memory;
    private final Consumer<Row> lockSubject;
    private final RunRetentionCoordinator coordinator;

    RunRetention(
            JdbcTemplate jdbc,
            TransactionTemplate tx,
            RunRepository rows,
            ObjectMapper json,
            PracticeMemoryAccess memory,
            Consumer<Row> lockSubject,
            RunRetentionCoordinator coordinator) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.rows = rows;
        this.policyJson = json;
        this.memory = memory;
        this.lockSubject = lockSubject;
        this.coordinator = coordinator;
    }

    void prune() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException(
                    "Run retention must start outside a database transaction");
        var lease = coordinator.acquire();
        if (lease == null) return;
        try {
            jdbc.update(
                    "DELETE o FROM interview_run_outbox o JOIN interview_runs r ON r.id=o.run_id"
                        + " WHERE r.status NOT IN ('ACCEPTED','RUNNING') AND"
                        + " r.updated_at<TIMESTAMPADD(DAY,-7,CURRENT_TIMESTAMP(6))");
            if (!pruneWithdrawnMemory(lease)) return;
            // 保留用于去重的命令记录与可公开事件，避免旧请求键再次触发执行。
            for (var known : rows.retained()) {
                if (!coordinator.renew(lease)) return;
                clearTerminal(known);
            }
        } finally {
            coordinator.release(lease);
        }
    }

    private boolean pruneWithdrawnMemory(RunRetentionCoordinator.Lease lease) {
        if (!coordinator.renew(lease)) return false;
        var retained = rows.memoryRetained(lease.cursor());
        for (var known : retained) {
            if (!coordinator.renew(lease)) return false;
            var mapper = policyJson;
            var selection =
                    mapper.treeToValue(
                            mapper.readTree(known.checkpoint()).path("practiceMemory"),
                            PracticeMemoryAccess.Selection.class);
            try {
                memory.read(known.owner(), selection);
            } catch (MemoryRevokedException ex) {
                // 已撤销的标识与修订不能重新取得资格；清理在新的事务中执行。
                if (!coordinator.renew(lease)) return false;
                clearTerminal(known);
            }
        }
        return coordinator.advance(lease, retained.size() < 100 ? "" : retained.getLast().id());
    }

    private void clearTerminal(Row known) {
        // Redis 租约在事务前检查；暂停超过租期后的重复清理仍由行锁和终态判断保持幂等。
        tx.executeWithoutResult(
                s -> {
                    lockSubject.accept(known);
                    var row = rows.lock(known.owner(), known.id());
                    if (row.terminal()) clearPrivate(row.id());
                });
    }

    void clearPrivate(String id) {
        jdbc.update(
                "UPDATE interview_run_steps SET"
                    + " before_hash=NULL,after_hash=NULL,action_key=NULL,source_keys_json=NULL"
                    + " WHERE run_id=?",
                id);
        jdbc.update("DELETE FROM interview_tool_receipts WHERE run_id=?", id);
        jdbc.update("UPDATE interview_model_attempts SET response_json=NULL WHERE run_id=?", id);
        jdbc.update(
                "UPDATE interview_runs SET checkpoint_json=NULL,input_json='{}' WHERE id=?", id);
    }
}
