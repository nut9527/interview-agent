package cn.edu.whu.zhizhida.knowledge;

import cn.edu.whu.zhizhida.knowledge.api.KnowledgeImportJob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import cn.edu.whu.zhizhida.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 本地上传与共享文件任务的受理、领取、租约和确认记录。 */
@Repository
public class KnowledgeImportRepository {
    private final JdbcTemplate jdbc;
    private final String executor = UUID.randomUUID().toString();

    private final TransactionTemplate transactions;

    public KnowledgeImportRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.transactions = transactions;
        this.jdbc = jdbc;
    }

    record Stored(KnowledgeImportJob job, String draft) {}

    private static final String JOB_COLUMNS =
            """
            SELECT id, source_name, status, completed_pages, total_pages, message, document_id,
                   UNIX_TIMESTAMP(expires_at)*1000 AS expires_epoch
            """;

    private KnowledgeImportJob job(ResultSet row) throws SQLException {
        return new KnowledgeImportJob(
                row.getString("id"),
                row.getString("source_name"),
                row.getString("status"),
                row.getInt("completed_pages"),
                row.getInt("total_pages"),
                row.getString("message"),
                Instant.ofEpochMilli(row.getLong("expires_epoch")),
                row.getString("document_id"));
    }

    Optional<Stored> find(Long user, String id, boolean lock) {
        String sql =
                JOB_COLUMNS
                        + ", draft_json FROM knowledge_import_jobs WHERE user_id=? AND id=?"
                        + (lock ? " FOR UPDATE" : "");
        return jdbc
                .query(
                        sql,
                        (row, index) -> new Stored(job(row), row.getString("draft_json")),
                        user,
                        id)
                .stream()
                .findFirst();
    }

    List<KnowledgeImportJob> list(Long user) {
        return jdbc.query(
                JOB_COLUMNS
                        + """
                FROM knowledge_import_jobs WHERE user_id=? AND expires_at>CURRENT_TIMESTAMP(6)
                ORDER BY created_at DESC LIMIT 10
                """,
                (row, index) -> job(row),
                user);
    }

    int outstanding(Long user) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_import_jobs WHERE user_id=? AND status IN ('QUEUED','RUNNING','READY') AND expires_at>CURRENT_TIMESTAMP(6)",
                Integer.class,
                user);
    }

    void insert(Long user, String id, String source, Instant expiry) {
        jdbc.update(
                "INSERT INTO knowledge_import_jobs(id,user_id,source_name,status,expires_at,executor_id,lease_until) VALUES(?,?,?,'QUEUED',FROM_UNIXTIME(?),?,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 30 SECOND))",
                id,
                user,
                source,
                expiry.toEpochMilli() / 1000.0,
                executor);
    }

    boolean start(String id) {
        return jdbc.update(
                        "UPDATE knowledge_import_jobs SET status='RUNNING' WHERE id=? AND status='QUEUED' AND executor_id=? AND lease_until>CURRENT_TIMESTAMP(6)",
                        id,
                        executor)
                == 1;
    }

    Claim localClaim(String id,long owner,String name,Instant deadline) {
        return new Claim(id,owner,name,null,executor,deadline);
    }

    boolean renew(String id) {
        return jdbc.update(
                        "UPDATE knowledge_import_jobs SET lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 30 SECOND) WHERE id=? AND executor_id=? AND status IN ('QUEUED','RUNNING') AND lease_until>CURRENT_TIMESTAMP(6)",
                        id,
                        executor)
                == 1;
    }

    boolean processing(String id) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM knowledge_import_jobs WHERE id=? AND status IN ('QUEUED','RUNNING') AND lease_until>CURRENT_TIMESTAMP(6)",
                        Integer.class,
                        id)
                > 0;
    }

    void stop(Long user, String id, String status, String message) {
        jdbc.update(
                "UPDATE knowledge_import_jobs SET status=?,message=?,draft_json=NULL WHERE user_id=? AND id=? AND status IN "
                        + ("FAILED".equals(status)
                                ? "('QUEUED','RUNNING')"
                                : "('QUEUED','RUNNING','READY')"),
                status,
                message,
                user,
                id);
    }

    void imported(Long user, String id, String document) {
        jdbc.update(
                "UPDATE knowledge_import_jobs SET status='IMPORTED',document_id=?,draft_json=NULL WHERE user_id=? AND id=? AND status='READY'",
                document,
                user,
                id);
    }

    boolean active(Long user, String id) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM knowledge_import_jobs WHERE user_id=? AND id=? AND status='RUNNING' AND expires_at>CURRENT_TIMESTAMP(6) AND executor_id=? AND lease_until>CURRENT_TIMESTAMP(6)",
                        Integer.class,
                        user,
                        id,
                        executor)
                == 1;
    }

    void recover() {
        jdbc.update(
                "UPDATE knowledge_import_jobs SET status='FAILED',message='执行租约已失效，请重新上传。',draft_json=NULL WHERE file_ref IS NULL AND status IN ('QUEUED','RUNNING') AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP(6))");
    }

    void expire() {
        jdbc.update("DELETE FROM knowledge_import_jobs WHERE expires_at<=CURRENT_TIMESTAMP(6)");
    }
    public record Claim(String id, long owner, String name, String fileRef, String token, Instant deadline) { }
    void enqueue(long owner, String id, String name, String reference, long timeoutSeconds) {
        transactions.executeWithoutResult(tx -> {
            // 账号锁覆盖待办数量检查与插入，使多节点并发上传遵守同一账号的容量限制。
            jdbc.queryForObject("SELECT id FROM app_users WHERE id=? FOR UPDATE", Long.class, owner);
            if(outstanding(owner)>=3)
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,"最多保留 3 个待完成导入，请先核对或取消已有任务。");
            jdbc.update("""
                    INSERT INTO knowledge_import_jobs(id,user_id,source_name,status,file_ref,execution_deadline,expires_at)
                    VALUES(?,?,?,'QUEUED',?,TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)),TIMESTAMPADD(SECOND,1800,CURRENT_TIMESTAMP(6)))
                    """,id,owner,name,reference,timeoutSeconds);
        });
    }
    List<String> pending() {
        return jdbc.queryForList("SELECT id FROM knowledge_import_jobs WHERE file_ref IS NOT NULL AND status='QUEUED' AND next_attempt_at<=CURRENT_TIMESTAMP(6) AND notice_after<=CURRENT_TIMESTAMP(6) AND execution_deadline>CURRENT_TIMESTAMP(6) ORDER BY created_at LIMIT 16",String.class);
    }
    boolean reserveNotice(String id) {
        return jdbc.update("UPDATE knowledge_import_jobs SET notice_after=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)) WHERE id=? AND status='QUEUED' AND notice_after<=CURRENT_TIMESTAMP(6)",id)==1;
    }
    String reference(String id) { return jdbc.queryForObject("SELECT file_ref FROM knowledge_import_jobs WHERE id=?",String.class,id); }
    Optional<Claim> claim(String id) {
        return transactions.execute(tx -> {
            String token=UUID.randomUUID().toString();
            if(jdbc.update("""
                    UPDATE knowledge_import_jobs SET status='RUNNING',executor_id=?,attempts=attempts+1,
                      completed_pages=0,total_pages=0,message='',lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6))
                    WHERE id=? AND file_ref IS NOT NULL AND status='QUEUED' AND attempts<3
                      AND next_attempt_at<=CURRENT_TIMESTAMP(6) AND execution_deadline>CURRENT_TIMESTAMP(6) AND expires_at>CURRENT_TIMESTAMP(6)
                    """,token,id)!=1) return Optional.empty();
            return jdbc.query("SELECT *,UNIX_TIMESTAMP(execution_deadline)*1000 AS deadline_epoch FROM knowledge_import_jobs WHERE id=?",
                    (r,n)->new Claim(id,r.getLong("user_id"),r.getString("source_name"),r.getString("file_ref"),token,Instant.ofEpochMilli(r.getLong("deadline_epoch"))),id).stream().findFirst();
        });
    }
    // 租约、任务期限和执行令牌必须同时有效；取消、过期或重新领取后，旧执行者不能推进状态。
    private static final String OWNED=" WHERE id=? AND executor_id=? AND status='RUNNING' AND lease_until>CURRENT_TIMESTAMP(6) AND (execution_deadline IS NULL OR execution_deadline>CURRENT_TIMESTAMP(6)) AND expires_at>CURRENT_TIMESTAMP(6)";
    boolean renew(Claim c) {
        return jdbc.update("UPDATE knowledge_import_jobs SET lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6))"+OWNED,c.id(),c.token())==1;
    }
    void progress(Claim c,int done,int total) {
        jdbc.update("UPDATE knowledge_import_jobs SET completed_pages=?,total_pages=?"+OWNED,done,total,c.id(),c.token());
    }
    void ready(Claim c,String draft) {
        jdbc.update("UPDATE knowledge_import_jobs SET status='READY',draft_json=?,lease_until=NULL"+OWNED,draft,c.id(),c.token());
    }
    void failed(Claim c,String message,boolean retry) {
        jdbc.update("UPDATE knowledge_import_jobs SET status=IF(? AND attempts<3,'QUEUED','FAILED'),message=?,lease_until=NULL,executor_id=NULL,next_attempt_at=TIMESTAMPADD(SECOND,5,CURRENT_TIMESTAMP(6)),notice_after=CURRENT_TIMESTAMP(6)"+OWNED,retry,message,c.id(),c.token());
    }
    void recoverShared() {
        // 切换到 MQ 后仍可能有旧进程受理的本地上传。只在原租约失效后标记为需重新上传。
        recover();
        jdbc.update("""
                UPDATE knowledge_import_jobs SET status='FAILED',message='导入超过执行期限，请重新上传。',draft_json=NULL
                WHERE file_ref IS NOT NULL AND status IN ('QUEUED','RUNNING') AND (execution_deadline<=CURRENT_TIMESTAMP(6) OR expires_at<=CURRENT_TIMESTAMP(6))
                """);
        jdbc.update("""
                UPDATE knowledge_import_jobs SET status=IF(attempts<3,'QUEUED','FAILED'),executor_id=NULL,lease_until=NULL,
                  message=IF(attempts<3,'执行中断，等待重试。','执行重试次数已耗尽，请重新上传。'),notice_after=CURRENT_TIMESTAMP(6)
                WHERE file_ref IS NOT NULL AND status='RUNNING' AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP(6))
                """);
        jdbc.update("DELETE FROM knowledge_import_jobs WHERE expires_at<=CURRENT_TIMESTAMP(6) AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP(6))");
    }
    double pendingCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_import_jobs WHERE file_ref IS NOT NULL AND status='QUEUED'",Double.class); }
    double oldestSeconds() { return jdbc.queryForObject("SELECT COALESCE(MAX(TIMESTAMPDIFF(MICROSECOND,created_at,CURRENT_TIMESTAMP(6))/1000000.0),0) FROM knowledge_import_jobs WHERE file_ref IS NOT NULL AND status='QUEUED'",Double.class); }
    boolean retainsFile(String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_import_jobs WHERE id=? AND (status IN ('QUEUED','RUNNING') OR lease_until>CURRENT_TIMESTAMP(6))",Integer.class,id)>0;
    }
}
