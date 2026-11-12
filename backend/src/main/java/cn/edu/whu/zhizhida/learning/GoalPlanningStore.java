package cn.edu.whu.zhizhida.learning;

import cn.edu.whu.zhizhida.common.execution.ExecutionPort;
import cn.edu.whu.zhizhida.common.execution.RunFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Repository
public class GoalPlanningStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ExecutionPort runs;
    public GoalPlanningStore(JdbcTemplate jdbc, ObjectMapper json, ExecutionPort runs) { this.jdbc = jdbc; this.json = json; this.runs = runs; }

    @Transactional
    public ObjectNode open(ExecutionPort.Context context, String configuration, ObjectNode initial) {
        runs.requireActive(context);
        jdbc.update("INSERT INTO learning_goal_planning(run_id,owner_id,epoch,configuration_hash,checkpoint_json,deadline) "
                + "VALUES(?,?,?,?,?,TIMESTAMPADD(SECOND,70,CURRENT_TIMESTAMP(6))) ON DUPLICATE KEY UPDATE run_id=run_id",
                context.id(), context.owner(), context.epoch(), configuration, initial.toString());
        var row = jdbc.queryForMap("SELECT * FROM learning_goal_planning WHERE run_id=? AND owner_id=? FOR UPDATE", context.id(), context.owner());
        if (((Number) row.get("epoch")).longValue() > context.epoch()) throw new RunFailureException("RUN_LEASE_LOST");
        if (Boolean.TRUE.equals(row.get("pending")) || row.get("pending") instanceof Number n && n.intValue() != 0)
            throw new RunFailureException("MODEL_OUTCOME_UNKNOWN");
        if (!configuration.equals(row.get("configuration_hash"))) throw new RunFailureException("MODEL_CONFIG_CHANGED");
        jdbc.update("UPDATE learning_goal_planning SET epoch=? WHERE run_id=?", context.epoch(), context.id());
        return (ObjectNode) json.readTree(row.get("checkpoint_json").toString());
    }

    @Transactional
    public int reserve(ExecutionPort.Context context) {
        runs.requireActive(context);
        int changed = jdbc.update("UPDATE learning_goal_planning SET attempts=attempts+1,pending=TRUE "
                + "WHERE run_id=? AND owner_id=? AND epoch=? AND pending=FALSE AND attempts<3 AND deadline>CURRENT_TIMESTAMP(6)",
                context.id(), context.owner(), context.epoch());
        if (changed != 1) throw new RunFailureException("RUN_BUDGET");
        int attempt = jdbc.queryForObject("SELECT attempts FROM learning_goal_planning WHERE run_id=?", Integer.class, context.id());
        jdbc.update("INSERT INTO learning_goal_model_attempts(run_id,attempt,epoch) VALUES(?,?,?)", context.id(), attempt, context.epoch());
        return attempt;
    }

    @Transactional
    public void received(ExecutionPort.Context context, int attempt, ObjectNode checkpoint, String response, String usage) {
        save(context, checkpoint, 0, true);
        jdbc.update("UPDATE learning_goal_model_attempts SET status='SUCCEEDED',response_json=?,usage_json=?,finished_at=CURRENT_TIMESTAMP(6) "
                + "WHERE run_id=? AND attempt=? AND epoch=?", response, usage, context.id(), attempt, context.epoch());
    }

    @Transactional
    public void failed(ExecutionPort.Context context, int attempt, String code) {
        jdbc.update("UPDATE learning_goal_model_attempts SET status='FAILED',error_code=?,finished_at=CURRENT_TIMESTAMP(6) "
                + "WHERE run_id=? AND attempt=? AND epoch=?", code, context.id(), attempt, context.epoch());
    }

    @Transactional
    public void save(ExecutionPort.Context context, ObjectNode checkpoint, int tools, boolean response) {
        runs.requireActive(context);
        int changed = jdbc.update("UPDATE learning_goal_planning SET checkpoint_json=?,tools=tools+?,pending=FALSE "
                + "WHERE run_id=? AND owner_id=? AND epoch=? AND tools+?<=3 AND pending=? AND deadline>CURRENT_TIMESTAMP(6)",
                checkpoint.toString(), tools, context.id(), context.owner(), context.epoch(), tools, response);
        if (changed != 1) throw new RunFailureException("RUN_LEASE_LOST");
    }

    public long remainingMillis(ExecutionPort.Context context) {
        return jdbc.queryForObject("SELECT TIMESTAMPDIFF(MICROSECOND,CURRENT_TIMESTAMP(6),deadline) DIV 1000 "
                + "FROM learning_goal_planning WHERE run_id=? AND owner_id=? AND epoch=?",Long.class,context.id(),context.owner(),context.epoch());
    }
}
