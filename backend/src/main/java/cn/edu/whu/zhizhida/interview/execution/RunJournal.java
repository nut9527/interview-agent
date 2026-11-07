package cn.edu.whu.zhizhida.interview.execution;

import java.util.List;
import java.util.Objects;
import cn.edu.whu.zhizhida.interview.api.InterviewRun;
import cn.edu.whu.zhizhida.interview.api.InterviewRun.InterviewRunTrace;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunStore.Claim;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunStore.Row;
import cn.edu.whu.zhizhida.observability.AgentTelemetry;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** 检查点、事件序号及追踪步骤与业务变更一起提交。 */
final class RunJournal {
    private final JdbcTemplate jdbc;
    private final ObjectMapper policyJson;

    RunJournal(JdbcTemplate jdbc, ObjectMapper policyJson) {
        this.jdbc = jdbc;
        this.policyJson = policyJson;
    }

    void event(String id, String type, String phase, String message) {
        jdbc.update("UPDATE interview_runs SET next_event_seq=next_event_seq+1 WHERE id=?", id);
        jdbc.update(
                "INSERT INTO interview_run_events(run_id,seq,type,phase,message) SELECT id,next_event_seq,?,?,? FROM interview_runs WHERE id=?",
                type, phase, message, id);
    }

    void addStep(Row row, String decision, String tool, String outcome, int added, String before, String after,
            String action, String sources, Long receipt, boolean cached, String stop) {
        int next = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COALESCE(MAX(step_no),0)+1 FROM interview_run_steps WHERE run_id=?", Integer.class, row.id()));
        String attempt = jdbc.query("SELECT attempt_id FROM interview_model_attempts WHERE run_id=? ORDER BY ordinal DESC LIMIT 1",
                (r,n) -> r.getString(1), row.id()).stream().findFirst().orElse(null);
        jdbc.update("INSERT INTO interview_run_steps(owner_id,run_id,step_no,decision_type,tool_name,observation_status,"
                + "new_evidence,before_hash,after_hash,action_key,source_keys_json,attempt_id,receipt_id,cache_hit,model_attempts,tool_requests,stop_reason) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", row.owner(), row.id(), next, decision, tool, outcome, added,
                before, after, action, sources, attempt, receipt, cached, row.attempts(), row.tools(), stop);
    }

    InterviewRunTrace trace(Row row, boolean hidden) {
        String id = row.id();
        String policy = AgentLoopState.policy(policyJson.readTree(row.config()));
        if (hidden) return new InterviewRunTrace(id,policy,row.status(),true,"HIDDEN",null,0,0,0,0,List.of());
        var steps = jdbc.query("SELECT * FROM interview_run_steps WHERE owner_id=? AND run_id=? ORDER BY step_no LIMIT 8",
                (r,n) -> new InterviewRunTrace.Step(r.getInt("step_no"),r.getString("decision_type"),r.getString("tool_name"),
                        r.getString("observation_status"),r.getInt("new_evidence"),r.getBoolean("cache_hit"),r.getString("stop_reason")),row.owner(),id);
        int actions = (int)steps.stream().filter(s -> !s.decisionType().equals("STOP") && !s.decisionType().equals("PROPOSE")).count();
        String stop = steps.stream().filter(s -> s.decisionType().equals("STOP")).map(InterviewRunTrace.Step::stopReason)
                .findFirst().orElse(null);
        var counts = jdbc.queryForMap("SELECT COALESCE(actual_tool_executions,0) AS actual,COALESCE(tool_cache_hits,0) AS hits,"
                + "GREATEST(0,tool_executions-COALESCE(actual_tool_executions,0)-COALESCE(tool_cache_hits,0)-COALESCE(skipped_tool_requests,0)) AS unknown FROM interview_runs WHERE id=?",id);
        return new InterviewRunTrace(id,policy,row.status(),false,row.status().equals("SUCCEEDED") ? "VALIDATED"
                : row.terminal() ? "NONE" : "PENDING",stop,actions,((Number)counts.get("actual")).intValue(),
                ((Number)counts.get("hits")).intValue(),((Number)counts.get("unknown")).intValue(),steps);
    }

    List<InterviewRun.Event> events(Row row, long after) {
        String id = row.id();
        return jdbc.query("SELECT * FROM interview_run_events WHERE run_id=? AND seq>? ORDER BY seq LIMIT 64",
                (r, n) -> new InterviewRun.Event(id, r.getLong("seq"), r.getString("type"), r.getString("phase"),
                        r.getString("message"),
                        r.getString("type").equals("turn.committed") ? row.sessionId() : null),
                id, after);
    }

    AgentTelemetry.Parent traceParent(Claim claim) {
        return jdbc.query("SELECT trace_id,trace_parent_span_id,trace_sampled FROM interview_runs WHERE id=? AND owner_id=?",
                (r, n) -> new AgentTelemetry.Parent(r.getString(1), r.getString(2), r.getBoolean(3)),
                claim.id(), claim.owner()).stream().filter(AgentTelemetry.Parent::valid).findFirst().orElse(null);
    }

    void checkpoint(String id, String json, String phase) {
        jdbc.update("UPDATE interview_runs SET checkpoint_json=?,phase=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                json, phase, id);
    }
}
