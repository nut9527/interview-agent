package cn.edu.whu.zhizhida.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.common.ErrorCode;
import cn.edu.whu.zhizhida.common.PageRequest;
import cn.edu.whu.zhizhida.common.PageResult;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Chunk;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Hit;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Summary;

/** 按用户隔离资料查询，并维护正文、版本归档和可定位的原文分块；历史版本访问仍依赖现存资料的归属。 */
@Repository
public class KnowledgeRepository {
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    public record Archived(String id, int version) {
    }

    public KnowledgeRepository(JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    public void lockOwner(Long user) {
        if (jdbc.queryForList("SELECT id FROM app_users WHERE id=? FOR UPDATE", Long.class, user)
                .isEmpty())
            throw new BusinessException(ErrorCode.AUTH_REQUIRED, "登录状态已失效");
    }

    public boolean atCapacity(Long user) {
        // 账号锁串行化新增；当前读避免外层事务的旧快照放过刚提交的第 100 份资料。
        return jdbc.queryForList(
                "SELECT id FROM knowledge_documents WHERE user_id=? LIMIT 100 FOR"
                        + " SHARE",
                String.class,
                user)
                .size() >= 100;
    }

    public List<String> ownedIds(long owner, List<String> ids, boolean lock) {
        if (ids.isEmpty())
            return List.of();
        if (ids.size() > 5
                || ids.stream().anyMatch(Objects::isNull)
                || new HashSet<>(ids).size() != ids.size())
            throw new IllegalArgumentException("无效资料授权范围");
        var args = new ArrayList<Object>();
        args.add(owner);
        args.addAll(ids);
        return jdbc.queryForList(
                "SELECT id FROM knowledge_documents WHERE user_id=? AND id IN ("
                        + String.join(",", Collections.nCopies(ids.size(), "?"))
                        + ") ORDER BY id"
                        + (lock ? " FOR SHARE" : ""),
                String.class,
                args.toArray());
    }

    public void insert(Long user, KnowledgeDocument doc) {
        jdbc.update(
                "INSERT INTO knowledge_documents(id,user_id,title,source_name,content)"
                        + " VALUES(?,?,?,?,?)",
                doc.id(),
                user,
                doc.title(),
                doc.sourceName(),
                doc.content());
        archive(doc);
    }

    public boolean currentVersion(long owner, String id, int version, String sha256) {
        // 此前的普通查询可能建立过期的可重复读快照；FOR SHARE 通过当前读核对现有权限。
        var rows = jdbc.queryForList(
                "SELECT version,SHA2(content,256) AS content_hash FROM knowledge_documents"
                        + " WHERE user_id=? AND id=? FOR SHARE",
                owner,
                id);
        return !rows.isEmpty()
                && ((Number) rows.getFirst().get("version")).intValue() == version
                && sha256.equals(rows.getFirst().get("content_hash"));
    }

    private void archive(KnowledgeDocument doc) {
        jdbc.update(
                "INSERT INTO knowledge_versions(document_id,version,title,source_name,content)"
                        + " SELECT id,version,title,source_name,content FROM knowledge_documents WHERE"
                        + " id=?",
                doc.id());
        jdbc.batchUpdate(
                "INSERT INTO"
                        + " knowledge_chunks(id,document_id,version,ordinal,heading,start_offset,end_offset,start_line,end_line,content)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                doc.chunks(),
                200,
                (statement, c) -> {
                    statement.setString(1, c.id());
                    statement.setString(2, doc.id());
                    statement.setInt(3, doc.version());
                    statement.setInt(4, c.ordinal());
                    statement.setString(5, c.heading());
                    statement.setInt(6, c.startOffset());
                    statement.setInt(7, c.endOffset());
                    statement.setInt(8, c.startLine());
                    statement.setInt(9, c.endLine());
                    statement.setString(10, c.content());
                });
        // 同步监听器在当前事务内登记派生任务，此处不访问消息队列。
        events.publishEvent(new Archived(doc.id(), doc.version()));
    }

    public PageResult<Summary> page(Long user, PageRequest page) {
        long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_documents WHERE user_id=?",
                Long.class,
                user);
        var rows = jdbc.query(
                "SELECT id,title,source_name,version,updated_at FROM knowledge_documents"
                        + " WHERE user_id=? ORDER BY updated_at DESC,id LIMIT ? OFFSET ?",
                (r, n) -> new Summary(
                        r.getString(1),
                        r.getString(2),
                        r.getString(3),
                        r.getInt(4),
                        r.getTimestamp(5).toInstant()),
                user,
                page.size(),
                page.offset());
        return new PageResult<>(rows, total, page);
    }

    public Optional<KnowledgeDocument> find(Long user, String id) {
        var docs = jdbc.query(
                "SELECT * FROM knowledge_documents WHERE user_id=? AND id=?",
                (r, n) -> new KnowledgeDocument(
                        id,
                        r.getString("title"),
                        r.getString("source_name"),
                        r.getInt("version"),
                        r.getString("content"),
                        r.getTimestamp("updated_at").toInstant(),
                        List.of()),
                user,
                id);
        return docs.stream()
                .findFirst()
                .map(
                        d -> new KnowledgeDocument(
                                d.id(),
                                d.title(),
                                d.sourceName(),
                                d.version(),
                                d.content(),
                                d.updatedAt(),
                                chunks(user, id, d.version())));
    }

    private List<Chunk> chunks(Long user, String id, int version) {
        return jdbc.query(
                "SELECT c.* FROM knowledge_chunks c JOIN knowledge_documents d ON"
                        + " d.id=c.document_id WHERE d.user_id=? AND d.id=? AND c.version=? ORDER BY"
                        + " c.ordinal",
                (r, n) -> chunk(r),
                user,
                id,
                version);
    }

    public Optional<KnowledgeDocument> version(Long user, String id, int version) {
        var docs = jdbc.query(
                "SELECT v.* FROM knowledge_versions v JOIN knowledge_documents d ON"
                        + " d.id=v.document_id WHERE d.user_id=? AND d.id=? AND v.version=?",
                (r, n) -> new KnowledgeDocument(
                        id,
                        r.getString("title"),
                        r.getString("source_name"),
                        r.getInt("version"),
                        r.getString("content"),
                        r.getTimestamp("created_at").toInstant(),
                        List.of()),
                user,
                id,
                version);
        return docs.stream()
                .findFirst()
                .map(
                        d -> new KnowledgeDocument(
                                d.id(),
                                d.title(),
                                d.sourceName(),
                                d.version(),
                                d.content(),
                                d.updatedAt(),
                                chunks(user, id, version)));
    }

    boolean exists(Long owner, String id) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM knowledge_documents WHERE user_id=? AND id=?)",
                        Boolean.class,
                        owner,
                        id));
    }

    public PageResult<Summary> versions(Long user, String id, PageRequest page) {
        long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_versions v JOIN knowledge_documents d ON"
                        + " d.id=v.document_id WHERE d.user_id=? AND d.id=?",
                Long.class,
                user,
                id);
        var rows = jdbc.query(
                "SELECT v.title,v.source_name,v.version,v.created_at FROM"
                        + " knowledge_versions v JOIN knowledge_documents d ON"
                        + " d.id=v.document_id WHERE d.user_id=? AND d.id=? ORDER BY v.version"
                        + " DESC LIMIT ? OFFSET ?",
                (r, n) -> new Summary(
                        id,
                        r.getString("title"),
                        r.getString("source_name"),
                        r.getInt("version"),
                        r.getTimestamp("created_at").toInstant()),
                user,
                id,
                page.size(),
                page.offset());
        return new PageResult<>(rows, total, page);
    }

    public boolean edit(Long user, KnowledgeDocument doc, int expectedVersion) {
        if (jdbc.update(
                "UPDATE knowledge_documents SET"
                        + " content=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE"
                        + " user_id=? AND id=? AND version=?",
                doc.content(),
                user,
                doc.id(),
                expectedVersion) != 1)
            return false;
        // 条件更新持有资料行锁，直到同一事务内的全部归档记录写入完成。
        archive(doc);
        return true;
    }

    private Chunk chunk(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Chunk(
                r.getString("id"),
                r.getInt("ordinal"),
                r.getString("heading"),
                r.getInt("start_offset"),
                r.getInt("end_offset"),
                r.getInt("start_line"),
                r.getInt("end_line"),
                r.getString("content"));
    }

    public boolean rename(Long user, String id, String title) {
        return jdbc.update(
                "UPDATE knowledge_documents SET title=?,updated_at=CURRENT_TIMESTAMP(6)"
                        + " WHERE user_id=? AND id=?",
                title,
                user,
                id) == 1;
    }

    public boolean delete(Long user, String id) {
        return jdbc.update("DELETE FROM knowledge_documents WHERE user_id=? AND id=?", user, id) == 1;
    }

    public List<Hit> candidates(Long user, List<String> ids, List<String> tokens) {
        if (tokens.isEmpty())
            return List.of();
        List<Object> args = new ArrayList<>();
        args.add(user);
        String scope = "";
        if (!ids.isEmpty()) {
            scope = " AND d.id IN (" + String.join(",", Collections.nCopies(ids.size(), "?")) + ")";
            args.addAll(ids);
        }
        return candidates(args, " AND c.version=d.version" + scope, tokens);
    }

    public List<Hit> versionCandidates(
            Long user, List<KnowledgeDocument> snapshots, List<String> tokens) {
        if (snapshots.isEmpty() || tokens.isEmpty())
            return List.of();
        List<Object> args = new ArrayList<>();
        args.add(user);
        List<String> versions = new ArrayList<>();
        for (var doc : snapshots) {
            versions.add("(d.id=? AND c.version=?)");
            args.add(doc.id());
            args.add(doc.version());
        }
        return candidates(args, " AND (" + String.join(" OR ", versions) + ")", tokens).stream()
                .map(
                        hit -> {
                            var doc = snapshots.stream()
                                    .filter(d -> d.id().equals(hit.documentId()))
                                    .findFirst()
                                    .orElseThrow();
                            return new Hit(
                                    doc.id(),
                                    doc.title(),
                                    doc.sourceName(),
                                    doc.version(),
                                    hit.chunk(),
                                    hit.score());
                        })
                .toList();
    }

    private List<Hit> candidates(List<Object> args, String scope, List<String> tokens) {
        List<String> conditions = new ArrayList<>();
        for (String token : tokens) {
            conditions.add("(c.content LIKE ? ESCAPE '!' OR c.heading LIKE ? ESCAPE '!')");
            // LIKE 通配符按字面量转义，避免用户关键词扩大匹配范围。
            String term = "%" + token.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            args.add(term);
            args.add(term);
        }
        return jdbc.query(
                "SELECT c.*,d.id AS doc_id,d.title,d.source_name FROM knowledge_chunks c JOIN"
                        + " knowledge_documents d ON c.document_id=d.id WHERE d.user_id=?"
                        + scope
                        + " AND ("
                        + String.join(" OR ", conditions)
                        + ") ORDER BY d.id,c.ordinal",
                (r, n) -> new Hit(
                        r.getString("doc_id"),
                        r.getString("title"),
                        r.getString("source_name"),
                        r.getInt("version"),
                        chunk(r),
                        0),
                args.toArray());
    }
}
