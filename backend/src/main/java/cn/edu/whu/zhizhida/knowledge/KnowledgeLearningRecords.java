package cn.edu.whu.zhizhida.knowledge;

import cn.edu.whu.zhizhida.common.UserInputException;

import java.util.Arrays;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.common.PageRequest;
import cn.edu.whu.zhizhida.common.PageResult;
import cn.edu.whu.zhizhida.common.ResourceNotFoundException;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeAnswerRecord.Source;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning.Confirm;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning.Feedback;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning.FeedbackRequest;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning.Organization;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning.Organize;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning.Question;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning.Reason;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning.SelfTest;
import tools.jackson.databind.ObjectMapper;

/** 按用户归属保存资料分类、自测题和问答反馈；读取时重新标记来源版本是否仍可访问。 */
@Service
public class KnowledgeLearningRecords {
    private final JdbcTemplate jdbc;
    private final CurrentUserProvider users;
    private final ObjectMapper json;
    private final KnowledgeService documents;
    private final KnowledgeAnswerHistory history;

    public KnowledgeLearningRecords(JdbcTemplate jdbc, CurrentUserProvider users, ObjectMapper json,
            KnowledgeService documents, KnowledgeAnswerHistory history) {
        this.jdbc = jdbc;
        this.users = users;
        this.json = json;
        this.documents = documents;
        this.history = history;
    }

    public List<Organization> organization() {
        return jdbc.query(
                "SELECT o.* FROM knowledge_organization o JOIN knowledge_documents d ON d.id=o.document_id WHERE d.user_id=?",
                (r, n) -> new Organization(r.getString("document_id"), r.getString("category"),
                        Arrays.asList(json.readValue(r.getString("tags_json"), String[].class))),
                users.requireUserId());
    }

    @Transactional
    public void organize(Organize request) {
        for (String id : request.documentIds().stream().distinct().sorted().toList()) {
            if (jdbc.queryForList("SELECT id FROM knowledge_documents WHERE user_id=? AND id=? FOR UPDATE",
                    String.class, users.requireUserId(), id).isEmpty())
                throw new ResourceNotFoundException("资料不存在");
            jdbc.update(
                    "INSERT INTO knowledge_organization(document_id,category,tags_json) VALUES(?,?,?) ON DUPLICATE KEY UPDATE category=VALUES(category),tags_json=VALUES(tags_json)",
                    id, request.category().strip(),
                    json.writeValueAsString(request.tags().stream().map(String::strip).distinct().toList()));
        }
    }

    @Transactional
    public List<SelfTest> store(List<SelfTest> questions) {
        // 按账号串行检查保存数量上限；生成过程中不持有数据库事务。
        jdbc.queryForList("SELECT id FROM app_users WHERE id=? FOR UPDATE", Long.class, users.requireUserId());
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_self_tests WHERE user_id=?", Long.class,
                users.requireUserId());
        if (total + questions.size() > 500)
            throw new UserInputException("最多保留 500 道自测题（含待确认题），请先清理。");
        for (var question : questions)
            jdbc.update("INSERT INTO knowledge_self_tests(id,user_id,question_json) VALUES(?,?,?)", question.id(),
                    users.requireUserId(), json.writeValueAsString(question));
        return questions;
    }

    public PageResult<SelfTest> questions(boolean confirmed, PageRequest page) {
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_self_tests WHERE user_id=? AND confirmed=?",
                Long.class, users.requireUserId(), confirmed);
        var rows = jdbc.query(
                "SELECT question_json FROM knowledge_self_tests WHERE user_id=? AND confirmed=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                (r, n) -> available(json.readValue(r.getString(1), SelfTest.class)), users.requireUserId(), confirmed,
                page.size(), page.offset());
        return new PageResult<>(rows, total, page);
    }

    private SelfTest available(SelfTest question) {
        return new SelfTest(question.id(), question.question(), question.confirmed(), question.executionMode(),
                question.sources().stream().map(this::available).toList());
    }

    private Source available(Source source) {
        boolean present = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_versions v JOIN knowledge_documents d ON d.id=v.document_id WHERE d.user_id=? AND d.id=? AND v.version=?",
                Integer.class, users.requireUserId(), source.documentId(), source.version()) > 0;
        return new Source(source.documentId(), source.title(), source.sourceName(), source.version(), present);
    }

    @Transactional
    public SelfTest confirm(String id, Confirm request) {
        var rows = jdbc.queryForList(
                "SELECT question_json FROM knowledge_self_tests WHERE user_id=? AND id=? FOR UPDATE", String.class,
                users.requireUserId(), id);
        if (rows.isEmpty())
            throw new ResourceNotFoundException("自测题不存在");
        var original = json.readValue(rows.getFirst(), SelfTest.class);
        if (original.confirmed())
            return available(original);
        if (!request.confirmed())
            throw new UserInputException("请逐题核对后确认");
        // 用户可修订题干和参考要点；原始引用与执行模式保留，便于回溯模型生成时的依据。
        var edited = new Question(request.stem().strip(), request.point().strip(),
                request.referencePoints().stream().map(String::strip).toList(), original.question().citations());
        var saved = new SelfTest(id, edited, true, original.executionMode(), original.sources());
        jdbc.update("UPDATE knowledge_self_tests SET confirmed=TRUE,question_json=? WHERE user_id=? AND id=?",
                json.writeValueAsString(saved), users.requireUserId(), id);
        return available(saved);
    }

    public void deleteQuestion(String id) {
        if (jdbc.update("DELETE FROM knowledge_self_tests WHERE user_id=? AND id=?", users.requireUserId(), id) != 1)
            throw new ResourceNotFoundException("自测题不存在");
    }

    public Feedback feedback(String id) {
        var answer = history.find(id);
        var rows = jdbc.query(
                "SELECT f.* FROM knowledge_answer_feedback f JOIN knowledge_answers a ON a.id=f.answer_id WHERE a.user_id=? AND a.id=?",
                (r, n) -> new Feedback(id, Arrays.asList(json.readValue(r.getString("reasons_json"), Reason[].class)),
                        r.getString("comment"),
                        Arrays.stream(json.readValue(r.getString("missing_sources_json"), Source[].class))
                                .map(this::available).toList(),
                        answer.question()),
                users.requireUserId(), id);
        return rows.isEmpty() ? new Feedback(id, List.of(), "", List.of(), answer.question()) : rows.getFirst();
    }

    @Transactional
    public Feedback feedback(String id, FeedbackRequest request) {
        history.find(id);
        var missing = request.missingDocumentIds().stream().distinct().map(documents::find)
                .map(d -> new Source(d.id(), d.title(), d.sourceName(), d.version(), true)).toList();
        if (!missing.isEmpty() && !request.reasons().contains(Reason.MISSING_MATERIAL))
            throw new UserInputException("请先标记漏掉相关资料");
        int changed = jdbc.update(
                "INSERT INTO knowledge_answer_feedback(answer_id,reasons_json,comment,missing_sources_json) SELECT id,?,?,? FROM knowledge_answers WHERE user_id=? AND id=? ON DUPLICATE KEY UPDATE reasons_json=VALUES(reasons_json),comment=VALUES(comment),missing_sources_json=VALUES(missing_sources_json),updated_at=CURRENT_TIMESTAMP(6)",
                json.writeValueAsString(request.reasons().stream().distinct().toList()), request.comment(),
                json.writeValueAsString(missing), users.requireUserId(), id);
        if (changed == 0)
            history.find(id);
        return feedback(id);
    }

    public PageResult<Feedback> feedbacks(PageRequest page) {
        long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_answer_feedback f JOIN knowledge_answers a ON a.id=f.answer_id WHERE a.user_id=?",
                Long.class, users.requireUserId());
        var ids = jdbc.queryForList(
                "SELECT f.answer_id FROM knowledge_answer_feedback f JOIN knowledge_answers a ON a.id=f.answer_id WHERE a.user_id=? ORDER BY f.updated_at DESC,f.answer_id LIMIT ? OFFSET ?",
                String.class, users.requireUserId(), page.size(), page.offset());
        return new PageResult<>(ids.stream().map(this::feedback).toList(), total, page);
    }
}
