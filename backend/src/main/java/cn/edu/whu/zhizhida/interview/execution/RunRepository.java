package cn.edu.whu.zhizhida.interview.execution;

import cn.edu.whu.zhizhida.common.ResourceNotFoundException;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunStore.Row;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** 运行记录的 SQL 查询与行映射，不开启事务、不协调模型或其他模块。 */
final class RunRepository {
    // 扫描、领取和到期回收必须共享资格定义；活跃租约始终由当前执行者负责。
    static final String CLAIMABLE = "(status='ACCEPTED' OR (status='RUNNING' AND lease_until<=CURRENT_TIMESTAMP(6)))";
    static final String READY = "(not_before IS NULL OR not_before<=CURRENT_TIMESTAMP(6) OR deadline<=CURRENT_TIMESTAMP(6))";
    static final String EXPIRED = "(deadline<=CURRENT_TIMESTAMP(6) OR (first_started_at IS NULL AND queue_deadline<=CURRENT_TIMESTAMP(6)))";
    private final JdbcTemplate jdbc;

    RunRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private final org.springframework.jdbc.core.RowMapper<Row> mapper = (r, n) -> new Row(r.getString("id"),
            r.getLong("owner_id"),
            r.getString("operation"), r.getString("request_id"), r.getString("request_hash"), r.getString("input_hash"),
            r.getString("session_id"),
            r.getInt("expected_turn"), r.getString("route"), r.getString("config_json"), r.getString("input_json"),
            r.getString("checkpoint_json"),
            r.getString("status"), r.getString("phase"), r.getTimestamp("deadline").toInstant(),
            r.getLong("lease_epoch"), r.getInt("model_attempts"),
            r.getInt("tool_batches"), r.getInt("tool_executions"), r.getInt("repairs"), r.getLong("next_event_seq"),
            r.getString("error_code"), r.getString("subject_type"), r.getString("subject_id"), r.getLong("subject_revision"));

    Row get(long owner, String id) {
        return jdbc.query("SELECT * FROM interview_runs WHERE owner_id=? AND id=?", mapper, owner, id).stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("interview run not found"));
    }

    List<Row> active(long owner) {
        return jdbc.query(
                "SELECT * FROM interview_runs WHERE owner_id=? AND subject_type='INTERVIEW' AND status IN ('ACCEPTED','RUNNING') ORDER BY created_at DESC LIMIT 20",
                mapper, owner);
    }

    Optional<Row> command(long owner, String op, String key) {
        return jdbc.query("SELECT * FROM interview_runs WHERE owner_id=? AND operation=? AND request_id=?", mapper,
                owner, op, key).stream().findFirst();
    }

    Optional<Row> commandLocked(long owner, String op, String key) {
        return jdbc.query("SELECT * FROM interview_runs WHERE owner_id=? AND operation=? AND request_id=? FOR UPDATE",
                mapper, owner, op, key).stream().findFirst();
    }

    List<Row> candidates() {
        return jdbc.query(
                "SELECT * FROM interview_runs WHERE " + CLAIMABLE + " AND " + READY + " ORDER BY created_at,id LIMIT 64",
                mapper);
    }

    Optional<Row> dispatchCandidate(String id) {
        return jdbc.query("SELECT * FROM interview_runs WHERE id=?",mapper,id).stream().findFirst();
    }

    void remindSoon(String id) {
        jdbc.update("UPDATE interview_runs SET next_dispatch_at=LEAST(next_dispatch_at,TIMESTAMPADD(SECOND,1,CURRENT_TIMESTAMP(6))) "
                + "WHERE id=? AND status='ACCEPTED'", id);
    }

    List<Row> forSession(long owner,String session) {
        return jdbc.query("SELECT * FROM interview_runs WHERE owner_id=? AND session_id=? ORDER BY created_at DESC,id DESC LIMIT 20",
                mapper,owner,session);
    }

    Row lock(long owner, String id) {
        return jdbc.query("SELECT * FROM interview_runs WHERE owner_id=? AND id=? FOR UPDATE", mapper, owner, id)
                .stream().findFirst()
                .orElseThrow(() -> new cn.edu.whu.zhizhida.common.execution.RunFailureException("RUN_GONE"));
    }

    long remainingMillis(String id) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT TIMESTAMPDIFF(MICROSECOND,CURRENT_TIMESTAMP(6),deadline) DIV 1000 FROM interview_runs WHERE id=?",
                Long.class, id));
    }

    String materials(long owner, String session) {
        return jdbc
                .query("SELECT material_manifest_json FROM interview_sessions WHERE user_id=? AND session_id=?",
                        (r, n) -> r.getString(1), owner, session)
                .stream().filter(Objects::nonNull).findFirst().orElse("[]");
    }

    void deleteSession(long owner, String session) {
        jdbc.update("DELETE FROM interview_runs WHERE owner_id=? AND session_id=?", owner, session);
    }

    void deleteOwned(long owner) {
        jdbc.queryForList("SELECT session_id FROM interview_sessions WHERE user_id=? ORDER BY session_id FOR UPDATE",
                String.class, owner);
        jdbc.update("DELETE FROM interview_runs WHERE owner_id=?", owner);
    }

    List<Row> find(long owner, String id, boolean locked) {
        return jdbc.query("SELECT * FROM interview_runs WHERE owner_id=? AND id=?" + (locked ? " FOR UPDATE" : ""), mapper, owner, id);
    }

    List<Row> recoverable() {
        return jdbc.query("SELECT * FROM interview_runs WHERE (next_dispatch_at<=CURRENT_TIMESTAMP(6) OR deadline<=CURRENT_TIMESTAMP(6)) AND "
                + READY + " AND " + CLAIMABLE + " ORDER BY next_dispatch_at LIMIT 32", mapper);
    }

    List<Row> expired() {
        return jdbc.query("SELECT * FROM interview_runs WHERE "
                + CLAIMABLE + " AND " + EXPIRED + " ORDER BY created_at LIMIT 64", mapper);
    }

    List<Row> retained() {
        return jdbc.query("SELECT * FROM interview_runs WHERE status NOT IN ('RUNNING','ACCEPTED') "
                + "AND updated_at<TIMESTAMPADD(DAY,-7,CURRENT_TIMESTAMP(6)) AND (checkpoint_json IS NOT NULL OR input_json<>'{}') LIMIT 100", mapper);
    }

    List<Row> memoryRetained(String after) {
        return jdbc.query("SELECT * FROM interview_runs WHERE id>? AND status NOT IN ('RUNNING','ACCEPTED') "
                + "AND checkpoint_json IS NOT NULL AND JSON_CONTAINS_PATH(checkpoint_json,'one','$.practiceMemory.references[0]') "
                + "ORDER BY id LIMIT 100", mapper, after);
    }
}
