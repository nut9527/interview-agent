package cn.edu.whu.zhizhida.jobs;

import cn.edu.whu.zhizhida.common.PageRequest;
import cn.edu.whu.zhizhida.common.PageResult;
import cn.edu.whu.zhizhida.common.persistence.JsonColumnCodec;
import cn.edu.whu.zhizhida.jobs.api.TargetJob;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Optional;

@Repository
public class TargetJobRepository {
    private final JdbcTemplate jdbc;
    private final JsonColumnCodec json;
    public TargetJobRepository(JdbcTemplate jdbc, JsonColumnCodec json) { this.jdbc = jdbc; this.json = json; }

    public void insert(Long userId, TargetJob job) {
        jdbc.update("INSERT INTO user_target_jobs (id,user_id,title,company,source_name,source_url,deadline,jd_text,requirements_json) VALUES (?,?,?,?,?,?,?,?,?)",
                job.id(), userId, job.title(), job.company(), job.sourceName(), job.sourceUrl(), job.deadline(), job.jdText(), json.write(job.requirements()));
    }
    public boolean update(Long userId, TargetJob job) {
        return jdbc.update("UPDATE user_target_jobs SET title=?,company=?,source_name=?,source_url=?,deadline=?,jd_text=?,requirements_json=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP(6) WHERE user_id=? AND id=? AND revision=?",
                job.title(), job.company(), job.sourceName(), job.sourceUrl(), job.deadline(), job.jdText(), json.write(job.requirements()), userId, job.id(), job.revision()) == 1;
    }
    public Optional<TargetJob> find(Long userId, String id) {
        return jdbc.query("SELECT * FROM user_target_jobs WHERE user_id=? AND id=?", this::read, userId, id).stream().findFirst();
    }
    public PageResult<TargetJob.Summary> page(Long userId, PageRequest page) {
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM user_target_jobs WHERE user_id=?", Long.class, userId);
        var rows = jdbc.query("SELECT id,revision,title,company,source_name,deadline,updated_at FROM user_target_jobs WHERE user_id=? ORDER BY updated_at DESC,id DESC LIMIT ? OFFSET ?",
                (rs, n) -> new TargetJob.Summary(rs.getString("id"), rs.getLong("revision"), rs.getString("title"), rs.getString("company"),
                        rs.getString("source_name"), rs.getObject("deadline", LocalDate.class), rs.getTimestamp("updated_at").toInstant()), userId, page.size(), page.offset());
        return new PageResult<>(rows, total, page);
    }
    public boolean delete(Long userId, String id, long revision) {
        return jdbc.update("DELETE FROM user_target_jobs WHERE user_id=? AND id=? AND revision=?", userId, id, revision) == 1;
    }
    public void deleteAllByUserId(Long userId) { jdbc.update("DELETE FROM user_target_jobs WHERE user_id=?", userId); }
    private TargetJob read(ResultSet rs, int row) throws SQLException {
        return new TargetJob(rs.getString("id"), rs.getLong("revision"), rs.getString("title"), rs.getString("company"), rs.getString("source_name"),
                rs.getString("source_url"), rs.getObject("deadline", LocalDate.class), rs.getString("jd_text"),
                Arrays.asList(json.read(rs.getString("requirements_json"), TargetJob.Requirement[].class)), rs.getTimestamp("updated_at").toInstant());
    }
}
