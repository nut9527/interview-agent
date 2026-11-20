package cn.edu.whu.zhizhida.interview.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import cn.edu.whu.zhizhida.interview.api.KnowledgeSnapshot.Reference;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunStore.Row;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** 同一运行锁保护回执、执行计数及消费该结果的下一检查点。 */
final class RunToolReceipts {
    private final JdbcTemplate jdbc;
    private final ObjectMapper policyJson;
    private final RunJournal journal;

    RunToolReceipts(JdbcTemplate jdbc, ObjectMapper policyJson, RunJournal journal) {
        this.jdbc = jdbc;
        this.policyJson = policyJson;
        this.journal = journal;
    }

    Optional<String> cached(String id, String key) {
        return jdbc.query(
                "SELECT result_json FROM interview_tool_receipts WHERE run_id=? AND logical_call_key=? AND (execution_kind IS NULL OR execution_kind<>'SKIPPED') LIMIT 1",
                (r, n) -> r.getString(1), id, key).stream().findFirst();
    }

    List<Reference> knowledgeReceipts(String id) {
        var receipts = new ArrayList<Reference>();
        for (String result : jdbc.queryForList(
                "SELECT result_json FROM interview_tool_receipts WHERE run_id=? AND tool_name='search_knowledge' "
                + "AND execution_kind IN ('EXECUTED','CACHE') ORDER BY id", String.class, id)) {
            var payload = policyJson.readTree(result);
            if ("FOUND".equals(payload.path("status").asString()))
                for (var reference : payload.path("references"))
                    receipts.add(policyJson.treeToValue(reference, Reference.class));
        }
        return List.copyOf(receipts);
    }

    void save(Row row, String key, String callId, String name, String args, String source, String result,
            String checkpoint, String execution, AgentLoopState.Observation observation) {
        jdbc.update(
                "INSERT INTO interview_tool_receipts(run_id,logical_call_key,tool_call_id,tool_name,args_json,source_hash,result_json,execution_kind) VALUES(?,?,?,?,?,?,?,?)",
                row.id(), key, callId, name, args, source, result, execution);
        jdbc.update("UPDATE interview_runs SET actual_tool_executions=actual_tool_executions+?,tool_cache_hits=tool_cache_hits+?,skipped_tool_requests=skipped_tool_requests+? WHERE id=?",
                execution.equals("EXECUTED") ? 1 : 0,execution.equals("CACHE") ? 1 : 0,execution.equals("SKIPPED") ? 1 : 0,row.id());
        if (observation != null) {
            Long receipt = jdbc.queryForObject("SELECT id FROM interview_tool_receipts WHERE run_id=? AND tool_call_id=?",
                    Long.class, row.id(), callId);
            journal.addStep(row, observation.decision(), name, observation.outcome(), observation.newEvidence(),
                    observation.before(), observation.after(), observation.actionKey(), observation.sourceKeys(),
                    receipt, execution.equals("CACHE"), observation.stopReason());
        }
        journal.checkpoint(row.id(), checkpoint, "TOOL");
        journal.event(row.id(), "tool.completed", "TOOL", "资料核对完成");
    }
}
