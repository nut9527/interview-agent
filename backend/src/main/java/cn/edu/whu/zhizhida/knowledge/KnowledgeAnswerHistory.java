package cn.edu.whu.zhizhida.knowledge;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.common.PageRequest;
import cn.edu.whu.zhizhida.common.PageResult;
import cn.edu.whu.zhizhida.common.ResourceNotFoundException;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeAnswer;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeAnswerRecord;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeAnswerRecord.Source;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeAnswerRecord.Summary;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument;
import tools.jackson.databind.ObjectMapper;

/** 保存带资料版本的问答记录和追问关系，读取时根据当前权限更新来源可用性。 */
@Service
public class KnowledgeAnswerHistory {
        private final JdbcTemplate jdbc;
        private final ObjectMapper json;
        private final CurrentUserProvider users;

        public KnowledgeAnswerHistory(JdbcTemplate jdbc, ObjectMapper json, CurrentUserProvider users) {
                this.jdbc = jdbc;
                this.json = json;
                this.users = users;
        }

        @Transactional
        public KnowledgeAnswer save(String question, List<KnowledgeDocument> documents, KnowledgeAnswer answer) {
                return save(question, documents, answer, null);
        }

        @Transactional
        public KnowledgeAnswer save(String question, List<KnowledgeDocument> documents, KnowledgeAnswer answer,
                        String parentId) {
                String id = UUID.randomUUID().toString();
                var saved = new KnowledgeAnswer(answer.sufficient(), answer.message(), answer.claims(),
                                answer.executionMode(),
                                id);
                var sources = documents.stream()
                                .map(d -> new Source(d.id(), d.title(), d.sourceName(), d.version(), true))
                                .toList();
                // 账号外键协调记录插入与账号注销的并发，防止已注销账号重新产生记录。
                jdbc.update(
                                "INSERT INTO knowledge_answers(id,user_id,question,documents_json,answer_json,sufficient,execution_mode) VALUES(?,?,?,?,?,?,?)",
                                id, users.requireUserId(), question, json.writeValueAsString(sources),
                                json.writeValueAsString(saved),
                                saved.sufficient(), saved.executionMode());
                if (parentId != null && jdbc.update(
                                "INSERT INTO knowledge_answer_parents(answer_id,parent_id) SELECT ?,id FROM knowledge_answers WHERE user_id=? AND id=?",
                                id, users.requireUserId(), parentId) != 1)
                        throw new ResourceNotFoundException("前文问答已删除，请重新提问");
                return saved;
        }

        public PageResult<Summary> page(PageRequest page) {
                Long user = users.requireUserId();
                long total = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_answers WHERE user_id=?", Long.class,
                                user);
                var rows = jdbc.query(
                                "SELECT id,question,sufficient,execution_mode,UNIX_TIMESTAMP(created_at) AS created_epoch FROM knowledge_answers WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                                (r, n) -> new Summary(r.getString(1), r.getString(2), r.getBoolean(3), r.getString(4),
                                                createdAt(r)),
                                user, page.size(), page.offset());
                return new PageResult<>(rows, total, page);
        }

        public KnowledgeAnswerRecord find(String id) {
                Long user = users.requireUserId();
                var rows = jdbc.query(
                                "SELECT *,UNIX_TIMESTAMP(created_at) AS created_epoch FROM knowledge_answers WHERE user_id=? AND id=?",
                                (r, n) -> new KnowledgeAnswerRecord(id, r.getString("question"),
                                                Arrays.asList(json.readValue(r.getString("documents_json"),
                                                                Source[].class)),
                                                json.readValue(r.getString("answer_json"), KnowledgeAnswer.class),
                                                createdAt(r)),
                                user, id);
                var saved = rows.stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("问答记录不存在或已删除"));
                var sources = saved.documents().stream().map(s -> new Source(s.documentId(), s.title(), s.sourceName(),
                                s.version(),
                                jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM knowledge_versions v JOIN knowledge_documents d ON d.id=v.document_id WHERE d.user_id=? AND d.id=? AND v.version=?",
                                                Integer.class, user, s.documentId(), s.version()) > 0))
                                .toList();
                var parents = jdbc.queryForList("SELECT parent_id FROM knowledge_answer_parents WHERE answer_id=?",
                                String.class, id);
                return new KnowledgeAnswerRecord(saved.id(), saved.question(), sources, saved.answer(),
                                saved.createdAt(),
                                parents.isEmpty() ? null : parents.getFirst());
        }

        public void delete(String id) {
                if (jdbc.update("DELETE FROM knowledge_answers WHERE user_id=? AND id=?", users.requireUserId(),
                                id) != 1)
                        throw new ResourceNotFoundException("问答记录不存在或已删除");
        }

        private Instant createdAt(java.sql.ResultSet row) throws java.sql.SQLException {
                // 读取时间戳，避免 JDBC 与数据库会话的时区差异导致保存时间偏移。
                long micros = row.getBigDecimal("created_epoch").movePointRight(6).longValueExact();
                return Instant.ofEpochSecond(micros / 1_000_000, (micros % 1_000_000) * 1000);
        }
}
