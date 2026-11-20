package cn.edu.whu.zhizhida.interview.execution;

import static cn.edu.whu.zhizhida.interview.execution.InterviewRunStore.conflict;

import cn.edu.whu.zhizhida.common.execution.RunFailureException;
import cn.edu.whu.zhizhida.interview.InterviewSessionRepository;
import cn.edu.whu.zhizhida.interview.api.InterviewSession;
import cn.edu.whu.zhizhida.interview.api.QuestionCatalog;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunStore.Claim;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunStore.Row;
import cn.edu.whu.zhizhida.learning.api.PracticeMemoryAccess;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

import java.util.function.Consumer;

/** 在调用方持有有效租约及行锁的事务中保存业务结果和运行终态。 */
final class RunCommit {
    private static final Logger log = LoggerFactory.getLogger(RunCommit.class);
    private final JdbcTemplate jdbc;
    private final InterviewSessionRepository sessions;
    private final QuestionCatalog questions;
    private final PracticeMemoryAccess memory;
    private final ObjectMapper policyJson;
    private final RunTrainingTasks trainingTasks;
    private final RunJournal journal;
    private final RunAttemptLedger attempts;
    private final BusinessMetrics metrics;
    private final Consumer<Runnable> afterCommit;

    RunCommit(
            JdbcTemplate jdbc,
            InterviewSessionRepository sessions,
            QuestionCatalog questions,
            PracticeMemoryAccess memory,
            ObjectMapper json,
            RunTrainingTasks trainingTasks,
            RunJournal journal,
            RunAttemptLedger attempts,
            BusinessMetrics metrics,
            Consumer<Runnable> afterCommit) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.questions = questions;
        this.memory = memory;
        this.policyJson = json;
        this.trainingTasks = trainingTasks;
        this.journal = journal;
        this.attempts = attempts;
        this.metrics = metrics;
        this.afterCommit = afterCommit;
    }

    void commit(Claim c, Row row, InterviewSession candidate, boolean opening) {
        if (opening
                && candidate.agent() != null
                && candidate.agent().training() != null
                && candidate.agent().training().question() != null) {
            var question = candidate.agent().training().question();
            Integer expectedVersion =
                    candidate.agent().training().retry() == null ? question.version() : null;
            questions.lockPublishedForExecution(c.owner(), question.id(), expectedVersion);
        }
        var mapper = policyJson;
        var input = mapper.readTree(row.input());
        var selection =
                input.has("practiceMemory")
                        ? mapper.treeToValue(
                                input.path("practiceMemory"), PracticeMemoryAccess.Selection.class)
                        : PracticeMemoryAccess.Selection.none();
        if (selection.available()) memory.lockForCommit(c.owner(), selection);
        if (!opening) {
            int bound =
                    jdbc.update(
                            "UPDATE interview_sessions SET active_run_id=NULL WHERE"
                                    + " user_id=? AND session_id=? AND active_run_id=? AND"
                                    + " turn_count=? AND state='WAITING_ANSWER'",
                            c.owner(),
                            c.sessionId(),
                            c.id(),
                            row.expectedTurn());
            if (bound != 1) throw conflict();
        }
        sessions.save(c.owner(), candidate);
        if (opening) {
            var archive = input.path("materials");
            jdbc.update(
                    "UPDATE interview_sessions SET material_manifest_json=? WHERE"
                            + " session_id=? AND user_id=?",
                    archive.toString(),
                    candidate.sessionId(),
                    c.owner());
        }
        trainingTasks.committed(c.owner(), row.id(), candidate.sessionId());
        finish(row, "SUCCEEDED", null, "turn.committed", "本轮结果已保存");
        if (opening)
            jdbc.update(
                    "UPDATE interview_runs SET input_json=? WHERE id=?",
                    "{\"sessionId\":\"" + candidate.sessionId() + "\"}",
                    row.id());
    }

    /**
     * 在调用方已加锁的事务中，统一写入终态、最终事件并解除会话占用。
     *
     * <p>递增租约代次使迟到结果失去写入资格；监控计数和日志在事务提交后记录。
     */
    void finish(Row row, String status, String code, String type, String message) {
        if (!status.equals("SUCCEEDED")) trainingTasks.failed(row.owner(), row.id());
        int finished =
                jdbc.update(
                        "UPDATE interview_runs SET"
                            + " status=?,error_code=?,phase=IF(?='SUCCEEDED','COMMIT',phase),lease_epoch=lease_epoch+1,lease_until=NULL,not_before=NULL,updated_at=CURRENT_TIMESTAMP(6)"
                            + " WHERE id=? AND (?<>'SUCCEEDED' OR (status='RUNNING' AND"
                            + " lease_epoch=? AND lease_until>CURRENT_TIMESTAMP(6) AND"
                            + " deadline>CURRENT_TIMESTAMP(6) AND cancel_requested=FALSE))",
                        status,
                        code,
                        status,
                        row.id(),
                        status,
                        row.epoch());
        if (finished != 1) throw new RunFailureException("RUN_FENCED");
        if (status.equals("SUCCEEDED"))
            journal.addStep(
                    row,
                    "PROPOSE",
                    null,
                    "VALIDATED",
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    null);
        String reason = code;
        if (reason == null && row.checkpoint() != null)
            reason =
                    policyJson
                            .readTree(row.checkpoint())
                            .path("loop")
                            .path("stopReason")
                            .asString(null);
        if (reason == null) reason = status.equals("SUCCEEDED") ? "COMPLETED" : status;
        journal.addStep(row, "STOP", null, status, 0, null, null, null, null, null, false, reason);
        attempts.markInterrupted(row.id());
        jdbc.update(
                "UPDATE interview_sessions SET active_run_id=NULL WHERE user_id=? AND session_id=?"
                        + " AND active_run_id=?",
                row.owner(),
                row.sessionId(),
                row.id());
        journal.event(row.id(), type, status.equals("SUCCEEDED") ? "COMMIT" : row.phase(), message);
        // 使用数据库时间包含排队及恢复耗时；只有事务真正提交后才计一次终态。
        Long elapsed =
                jdbc.queryForObject(
                        "SELECT TIMESTAMPDIFF(MICROSECOND,created_at,CURRENT_TIMESTAMP(6)) DIV 1000"
                                + " FROM interview_runs WHERE id=?",
                        Long.class,
                        row.id());
        var outcome = BusinessMetrics.RunOutcome.valueOf(status);
        afterCommit.accept(
                () -> {
                    metrics.runFinished(outcome, elapsed == null ? 0 : elapsed);
                    log.info(
                            "runId={} epoch={} phase={} event=run.finished status={} code={}"
                                    + " elapsedMs={}",
                            row.id(),
                            row.epoch(),
                            status.equals("SUCCEEDED") ? "COMMIT" : row.phase(),
                            status,
                            code,
                            elapsed);
                });
    }
}
