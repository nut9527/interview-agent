package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.common.ErrorCode;
import cn.edu.whu.zhizhida.common.PageRequest;
import cn.edu.whu.zhizhida.common.PageResult;
import cn.edu.whu.zhizhida.common.ResourceNotFoundException;
import cn.edu.whu.zhizhida.common.SourceRevokedException;
import cn.edu.whu.zhizhida.common.UserInputException;
import cn.edu.whu.zhizhida.interview.api.InterviewService;
import cn.edu.whu.zhizhida.interview.api.InterviewService.InterviewStartRequest;
import cn.edu.whu.zhizhida.interview.api.InterviewMode;
import cn.edu.whu.zhizhida.interview.api.InterviewSession;
import cn.edu.whu.zhizhida.interview.api.InterviewSourceAccess;
import cn.edu.whu.zhizhida.interview.api.InterviewState;
import cn.edu.whu.zhizhida.interview.api.InterviewTraining;
import cn.edu.whu.zhizhida.interview.api.KnowledgeCatalog;
import cn.edu.whu.zhizhida.interview.api.QuestionCatalog;
import cn.edu.whu.zhizhida.interview.api.TrainingTask;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 管理复盘、题库和知识资料生成的训练任务，并在启动时核对所选来源版本与当前授权。 */
@Service
public class TrainingTaskService {
    private final JdbcTemplate jdbc;
    private final CurrentUserProvider users;
    private final InterviewService interviews;
    private final QuestionCatalog questions;
    private final InterviewSourceGuard sourceGuard;
    private final KnowledgeCatalog knowledge;
    private final InterviewSourceAccess sourceAccess;
    private final ObjectMapper json;

    public TrainingTaskService(
            JdbcTemplate jdbc,
            CurrentUserProvider users,
            InterviewService interviews,
            QuestionCatalog questions,
            InterviewSourceGuard sourceGuard,
            KnowledgeCatalog knowledge,
            InterviewSourceAccess sourceAccess,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.users = users;
        this.interviews = interviews;
        this.questions = questions;
        this.sourceGuard = sourceGuard;
        this.knowledge = knowledge;
        this.sourceAccess = sourceAccess;
        this.json = json;
    }

    private static final String SELECT =
            """
        SELECT t.*,s.state AS session_state,COALESCE(s.answered_count,0) AS answered_count,
        COALESCE(s.total_rounds,3) AS total_rounds FROM interview_training_tasks t
        LEFT JOIN interview_sessions s ON s.session_id=t.active_session_id AND s.user_id=t.user_id
        WHERE t.user_id=?
        """;

    private TrainingTask row(ResultSet r, int n) throws SQLException {
        String status =
                "FINISHED".equals(r.getString("session_state"))
                        ? "COMPLETED"
                        : r.getString("status");
        if (r.getString("active_session_id") == null && "ACTIVE".equals(status)) status = "TODO";
        return new TrainingTask(
                r.getString("id"),
                Objects.requireNonNullElse(r.getString("source_session_id"), ""),
                r.getInt("source_round"),
                r.getString("question_id"),
                r.getString("title"),
                r.getString("gap_text"),
                status,
                r.getString("active_session_id"),
                r.getInt("answered_count"),
                r.getInt("total_rounds"),
                r.getTimestamp("created_at").toInstant(),
                r.getString("goal_id"),
                (Integer) r.getObject("question_version"),
                r.getString("source_type"),
                r.getString("source_document_id"),
                (Integer) r.getObject("source_document_version"),
                r.getString("training_topic") == null
                        ? null
                        : InterviewTraining.Topic.valueOf(r.getString("training_topic")));
    }

    public PageResult<TrainingTask> page(PageRequest page) {
        long user = users.requireUserId();
        long count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM interview_training_tasks WHERE user_id=?",
                        Long.class,
                        user);
        var rows =
                jdbc.query(
                        SELECT + " ORDER BY t.created_at DESC,t.id DESC LIMIT ? OFFSET ?",
                        this::row,
                        user,
                        page.size(),
                        page.offset());
        return new PageResult<>(rows, count, page);
    }

    public TrainingTask find(String id) {
        return jdbc.query(SELECT + " AND t.id=?", this::row, users.requireUserId(), id).stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("训练任务不存在"));
    }

    @Transactional
    public TrainingTask createDirect(TrainingTask.DirectCreate request) {
        long owner = users.requireUserId();
        boolean question = request.sourceType() == TrainingTask.DirectSource.QUESTION;
        boolean validQuestion =
                question
                        && request.questionId() != null
                        && request.questionVersion() != null
                        && request.documentId() == null
                        && request.documentVersion() == null
                        && request.trainingTopic() == null;
        boolean validDocument =
                request.sourceType() == TrainingTask.DirectSource.KNOWLEDGE
                        && request.documentId() != null
                        && request.documentVersion() != null
                        && request.trainingTopic() != null
                        && request.questionId() == null
                        && request.questionVersion() == null;
        if (!validQuestion && !validDocument) {
            throw new UserInputException("请仅提供该来源所需的题目版本，或资料版本与训练方向。");
        }
        // 先锁来源、再锁账号，与来源撤销和账号生命周期事务的顺序保持一致。
        if (!question) sourceAccess.lockForUse(owner, List.of(request.documentId()));
        jdbc.queryForList("SELECT id FROM app_users WHERE id=? FOR UPDATE", owner).stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("账号不存在"));
        String digest = InterviewRunStore.sha(json.writeValueAsString(request));
        var previous =
                jdbc.queryForList(
                        "SELECT id,request_hash FROM interview_training_tasks WHERE user_id=? AND request_id=? FOR UPDATE",
                        owner,
                        request.requestId());
        if (!previous.isEmpty()) {
            if (!digest.equals(previous.getFirst().get("request_hash")))
                throw new BusinessException(ErrorCode.CONFLICT, "请求键已用于另一训练来源。");
            return find((String) previous.getFirst().get("id"));
        }
        String title, sourceHash = null;
        if (question)
            title =
                    questions
                            .requirePublished(request.questionId(), request.questionVersion())
                            .title();
        else {
            var doc =
                    knowledge
                            .snapshot(List.of(request.documentId()), request.trainingTopic().name())
                            .getFirst();
            if (doc.version() != request.documentVersion())
                throw new BusinessException(ErrorCode.CONFLICT, "资料版本已变化，请核对当前正文后重新加入计划。");
            sourceAccess.requireCurrentVersion(owner, doc.id(), doc.version(), doc.sha256());
            title = doc.title();
            sourceHash = doc.sha256();
        }
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM interview_training_tasks WHERE user_id=?",
                        Integer.class,
                        owner)
                >= 500) throw new UserInputException("最多保存 500 项训练任务，请先清理。");
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO interview_training_tasks(id,user_id,source_round,question_id,question_version,title,gap_text,source_type,source_document_id,source_document_version,source_document_hash,training_topic,request_id,request_hash) VALUES(?,?,0,?,?,?,?,?,?,?,?,?,?,?)",
                id,
                owner,
                question ? request.questionId() : "",
                request.questionVersion(),
                title,
                question ? "按已确认题目版本完成三轮实际作答，再核对回答证据。" : "按已确认资料版本完成三轮专项作答；资料只作参考，不能证明能力达标。",
                request.sourceType().name(),
                request.documentId(),
                request.documentVersion(),
                sourceHash,
                request.trainingTopic() == null ? null : request.trainingTopic().name(),
                request.requestId(),
                digest);
        return find(id);
    }

    /** 持有授权锁，直到启动命令受理流程已保存本次校验的来源版本。 */
    public void lockDirectSource(TrainingTask task) {
        if ("KNOWLEDGE".equals(task.sourceType()))
            sourceAccess.lockForUse(users.requireUserId(), List.of(task.sourceDocumentId()));
    }

    public InterviewStartRequest startRequest(TrainingTask task) {
        if ("KNOWLEDGE".equals(task.sourceType())) {
            var doc =
                    knowledge
                            .snapshot(List.of(task.sourceDocumentId()), task.trainingTopic().name())
                            .getFirst();
            String expected =
                    jdbc.queryForObject(
                            "SELECT source_document_hash FROM interview_training_tasks WHERE user_id=? AND id=?",
                            String.class,
                            users.requireUserId(),
                            task.id());
            if (doc.version() != task.sourceDocumentVersion() || !doc.sha256().equals(expected))
                throw new BusinessException(
                        ErrorCode.CONFLICT, "计划引用的资料已更新，请核对新版本并重新建立任务；原任务不会静默换用正文。");
            sourceAccess.requireCurrentVersion(
                    users.requireUserId(), doc.id(), doc.version(), doc.sha256());
            return new InterviewStartRequest(
                    task.title(),
                    null,
                    null,
                    null,
                    InterviewMode.GENERAL,
                    3,
                    null,
                    null,
                    null,
                    task.trainingTopic(),
                    null,
                    null,
                    null,
                    null,
                    List.of(task.sourceDocumentId()));
        }
        return new InterviewStartRequest(
                task.title(),
                null,
                null,
                null,
                InterviewMode.GENERAL,
                3,
                null,
                null,
                null,
                null,
                task.questionId().isEmpty() ? task.sourceSessionId() : null,
                task.questionId().isEmpty() ? task.sourceRound() : null,
                task.questionId().isEmpty() ? null : task.questionId(),
                task.questionVersion());
    }

    @Transactional
    public TrainingTask create(TrainingTask.Create request) {
        var source = interviews.findSession(request.sourceSessionId());
        if (Boolean.TRUE.equals(source.sourceRevoked())) throw new SourceRevokedException();
        sourceGuard.lock(users.requireUserId(), source);
        if (source.state() != InterviewState.FINISHED || source.agent() == null)
            throw new UserInputException("请从已完成面试的复盘添加任务。");
        var evaluation =
                source.agent().evaluations().stream()
                        .filter(e -> e.round() == request.sourceRound())
                        .findFirst()
                        .orElseThrow(() -> new UserInputException("原回答轮次不存在"));
        String questionId = request.questionId() == null ? "" : request.questionId();
        var competency =
                source.agent().plan().stream()
                        .filter(c -> c.id().equals(evaluation.competencyId()))
                        .findFirst()
                        .orElseThrow();
        String title = competency.name();
        if (!questionId.isEmpty()) {
            var question = questions.requirePublished(questionId, null);
            if (!question.competencyId().equals(evaluation.competencyId()))
                throw new UserInputException("请选择与本轮能力相关的题目。");
            title = question.title();
        }
        long user = users.requireUserId();
        var existing =
                jdbc.queryForList(
                        "SELECT id FROM interview_training_tasks WHERE user_id=? AND source_session_id=? AND source_round=? AND question_id=?",
                        String.class,
                        user,
                        source.sessionId(),
                        request.sourceRound(),
                        questionId);
        if (!existing.isEmpty()) return find(existing.get(0));
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM interview_training_tasks WHERE user_id=?",
                        Long.class,
                        user)
                >= 500) throw new UserInputException("最多保存 500 项训练任务，请先清理。");
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO interview_training_tasks(id,user_id,source_session_id,source_round,question_id,title,gap_text) VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                id,
                user,
                source.sessionId(),
                request.sourceRound(),
                questionId,
                title,
                evaluation.decision().gap());
        return find(
                jdbc.queryForObject(
                        "SELECT id FROM interview_training_tasks WHERE user_id=? AND source_session_id=? AND source_round=? AND question_id=?",
                        String.class,
                        user,
                        source.sessionId(),
                        request.sourceRound(),
                        questionId));
    }

    public TrainingTask update(String id, TrainingTask.Update request) {
        var task = find(id);
        if ("COMPLETED".equals(task.status())) return task;
        String status =
                request.action() == TrainingTask.Action.PAUSE
                        ? "PAUSED"
                        : task.activeSessionId() == null ? "TODO" : "ACTIVE";
        int changed =
                jdbc.update(
                        "UPDATE interview_training_tasks SET status=?,updated_at=CURRENT_TIMESTAMP(6) WHERE user_id=? AND id=? AND (status<>'STARTING' OR updated_at<TIMESTAMPADD(MINUTE,-5,CURRENT_TIMESTAMP(6)))",
                        status,
                        users.requireUserId(),
                        id);
        if (changed != 1) throw new BusinessException(ErrorCode.CONFLICT, "任务正在启动，请稍后重试。");
        return find(id);
    }

    public void delete(String id) {
        if (jdbc.update(
                        "DELETE FROM interview_training_tasks WHERE user_id=? AND id=? AND status<>'STARTING'",
                        users.requireUserId(),
                        id)
                != 1) {
            find(id);
            throw new BusinessException(ErrorCode.CONFLICT, "任务正在启动，请稍后重试。");
        }
    }
}
