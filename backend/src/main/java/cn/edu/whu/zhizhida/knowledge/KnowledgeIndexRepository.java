package cn.edu.whu.zhizhida.knowledge;

import java.util.List;
import java.util.function.Consumer;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Repository;

/** MySQL 保存索引任务、删除墓碑与重试状态，会话锁防止远端索引被并发写入。 */
@Repository
public class KnowledgeIndexRepository {
    private final JdbcTemplate jdbc;

    public KnowledgeIndexRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Job(long id, String documentId, Integer version, String hash, String profile, Long owner) {
    }

    void enqueueVersion(String id, int version, String profile) {
        jdbc.update(
                """
                        INSERT IGNORE INTO knowledge_index_jobs(document_id,source_id,version,content_sha256,profile,profile_sha256)
                        SELECT document_id,document_id,version,SHA2(content,256),?,SHA2(?,256)
                        FROM knowledge_versions WHERE document_id=? AND version=?
                        """,
                profile, profile, id, version);
    }

    public void reconcile(String profile) {
        // 分批补齐导入、恢复及配置切换前的历史版本，限制单次扫描的写入量。
        jdbc.update(
                """
                        INSERT IGNORE INTO knowledge_index_jobs(document_id,source_id,version,content_sha256,profile,profile_sha256)
                        SELECT v.document_id,v.document_id,v.version,SHA2(v.content,256),?,SHA2(?,256)
                        FROM knowledge_versions v LEFT JOIN knowledge_index_jobs j
                          ON j.document_id=v.document_id AND j.version=v.version AND j.profile_sha256=SHA2(?,256)
                        WHERE j.id IS NULL ORDER BY v.document_id,v.version LIMIT 100
                        """,
                profile, profile, profile);
        jdbc.update("UPDATE knowledge_index_jobs SET state='QUEUED',attempts=0,next_at=CURRENT_TIMESTAMP(6) "
                + "WHERE source_id IS NOT NULL AND profile_sha256=SHA2(?,256) AND state='RETIRED'", profile);
    }

    // 资料删除优先清理；配置切换时须等同版本的新索引就绪，再退役旧配置，避免切换期间失去检索来源。
    private static final String ELIGIBLE = """
            j.next_at<=CURRENT_TIMESTAMP(6) AND (
              (j.source_id IS NULL AND j.state<>'DELETED') OR
              (j.profile_sha256=SHA2(?,256) AND j.state IN ('QUEUED','RUNNING','RETRY')) OR
              (j.profile_sha256<>SHA2(?,256) AND j.state NOT IN ('DELETED','RETIRED') AND EXISTS
                (SELECT 1 FROM knowledge_index_jobs n WHERE n.document_id=j.document_id AND n.version=j.version
                 AND n.profile_sha256=SHA2(?,256) AND n.state='READY')))
            """;

    List<Long> pending(String profile, boolean notices) {
        return jdbc.queryForList("SELECT j.id FROM knowledge_index_jobs j WHERE " + ELIGIBLE
                + (notices ? " AND j.notice_after<=CURRENT_TIMESTAMP(6)" : "")
                + " ORDER BY (j.source_id IS NULL) DESC,j.next_at,j.id LIMIT 16", Long.class, profile, profile,
                profile);
    }

    boolean reserveNotice(long id) {
        return jdbc.update(
                "UPDATE knowledge_index_jobs SET notice_after=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)) WHERE id=? AND notice_after<=CURRENT_TIMESTAMP(6)",
                id) == 1;
    }

    public boolean processNext(String profile, Consumer<Job> action) {
        var ids = pending(profile, false);
        for (long id : ids) {
            if (process(id, profile, action))
                return true;
        }
        return false;
    }

    public boolean process(long id, String profile, Consumer<Job> action) {
        Boolean processed = jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            // 锁绑定当前物理连接，避免慢任务被其他 Worker 抢占；进程退出释放锁后可恢复 RUNNING 任务。
            var session = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            String lock = "knowledge-index:" + id;
            if (!Integer.valueOf(1).equals(session.queryForObject("SELECT GET_LOCK(?,0)", Integer.class, lock)))
                return false;
            try {
                var jobs = session.query(
                        """
                                SELECT j.*,d.user_id FROM knowledge_index_jobs j LEFT JOIN knowledge_documents d ON d.id=j.source_id
                                WHERE j.id=? AND
                                """
                                + ELIGIBLE,
                        (r, n) -> new Job(id, r.getString("document_id"), r.getInt("version"),
                                r.getString("content_sha256"), r.getString("profile"),
                                r.getObject("user_id", Long.class)),
                        id, profile, profile, profile);
                if (jobs.isEmpty())
                    return false;
                var job = jobs.getFirst();
                if (job.owner() != null && job.profile().equals(profile)
                        && "READY".equals(session.queryForObject("SELECT state FROM knowledge_index_jobs WHERE id=?",
                                String.class, id)))
                    return false;
                session.update(
                        "UPDATE knowledge_index_jobs SET state='RUNNING',attempts=attempts+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                        id);
                try {
                    action.accept(job);
                    String state = job.owner() == null ? "DELETED"
                            : job.profile().equals(profile) ? "READY" : "RETIRED";
                    session.update(
                            "UPDATE knowledge_index_jobs SET state=?,error_code=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                            state, id);
                } catch (RuntimeException ex) {
                    // 异常正文可能含资料或凭据，只持久化固定错误分类。
                    session.update("""
                            UPDATE knowledge_index_jobs SET state='RETRY',error_code='INDEX_IO_FAILURE',
                            next_at=TIMESTAMPADD(SECOND,LEAST(300,POW(2,LEAST(attempts,8))),CURRENT_TIMESTAMP(6)),
                            updated_at=CURRENT_TIMESTAMP(6) WHERE id=?
                            """, id);
                }
                return true;
            } finally {
                session.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, lock);
            }
        });
        return Boolean.TRUE.equals(processed);
    }

    public void requeue(long owner, String document, String profile) {
        jdbc.update("""
                UPDATE knowledge_index_jobs j JOIN knowledge_documents d ON d.id=j.source_id
                SET j.state='QUEUED',j.attempts=0,j.next_at=CURRENT_TIMESTAMP(6),j.notice_after=CURRENT_TIMESTAMP(6)
                WHERE d.user_id=? AND d.id=? AND j.profile_sha256=SHA2(?,256) AND j.state<>'RUNNING'
                """, owner, document, profile);
    }

    public List<java.util.Map<String, Object>> status(long owner, String document) {
        return jdbc.queryForList("""
                SELECT j.version,j.profile,j.state,j.attempts,j.error_code,j.updated_at FROM knowledge_index_jobs j
                JOIN knowledge_documents d ON d.id=j.source_id WHERE d.user_id=? AND d.id=? ORDER BY j.version,j.id
                """, owner, document);
    }
}
