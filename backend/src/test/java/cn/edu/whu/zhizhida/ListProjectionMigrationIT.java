package cn.edu.whu.zhizhida;

import db.migration.V17__backfill_job_list_fields;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListProjectionMigrationIT extends IntegrationTestBase {
    @Autowired DataSource dataSource;

    @Test void legacyRowsAreBackfilledFromTheirOwnSnapshotsWithoutChangingTheirContents() throws Exception {
        // 连接内的临时表遮蔽同名测试表，作用范围仅限当前连接。
        var created = new ArrayList<String>();
        try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
            try {
                for (String table : List.of("resume_assessments", "career_reports", "resume_versions", "interview_sessions", "job_postings")) {
                    sql.execute("CREATE TEMPORARY TABLE migration_copy LIKE " + table);
                    created.add("migration_copy");
                    sql.execute("CREATE TEMPORARY TABLE " + table + " LIKE migration_copy");
                    created.add(table);
                    sql.execute("DROP TEMPORARY TABLE migration_copy");
                    created.remove("migration_copy");
                }
                sql.execute("ALTER TABLE resume_assessments DROP COLUMN owner_name,DROP COLUMN target_role,DROP COLUMN source,DROP COLUMN resume_revision,DROP COLUMN rule_version,DROP COLUMN total_score,DROP INDEX idx_assessment_page");
                sql.execute("ALTER TABLE career_reports DROP COLUMN rule_version");
                sql.execute("ALTER TABLE interview_sessions DROP COLUMN answered_count,DROP COLUMN preview,DROP INDEX idx_interview_page");
                sql.execute("ALTER TABLE job_postings DROP COLUMN description_preview,DROP COLUMN closing_date,DROP COLUMN closed,DROP COLUMN salary_value,DROP INDEX idx_job_page");
                String assessment = "{\"source\":\"SAVED_REVISION\",\"input\":{\"resume\":{\"ownerName\":\"旧姓名\"}},\"result\":{\"resumeRevision\":4,\"ruleVersion\":\"old-v1\",\"totalScore\":60,\"roleFit\":{\"targetRole\":\"旧方向\"}}}";
                try (var insert = connection.prepareStatement("INSERT INTO resume_assessments(id,user_id,snapshot_json) VALUES ('legacy',1,?)")) {
                    insert.setString(1, assessment); insert.executeUpdate();
                }
                sql.execute("INSERT INTO interview_sessions(session_id,user_id,target_role,state,turn_count,turns_json) VALUES ('legacy',1,'Java','FINISHED',2,'[{\"speaker\":\"interviewer\",\"content\":\"旧问题\"},{\"speaker\":\"user\",\"content\":\"旧回答\"},{\"speaker\":\"interviewer\",\"content\":\"最后反馈\"}]')");
                sql.execute("INSERT INTO career_reports(report_id,user_id,owner_name,target_role,total_score,readiness_level,report_json) VALUES ('legacy',1,'旧姓名','旧方向',60,'旧版','{}')");
                sql.execute("INSERT INTO job_postings(id,title,company,salary,required_skills_json,description,source_type,valid_through) VALUES (123,'旧岗位','旧公司','10k-20k','[]','招聘已结束','REMOTE','2026年9月30日')");
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V16__list_projections.sql"));
                Context context = mock(Context.class); when(context.getConnection()).thenReturn(connection);
                new V17__backfill_job_list_fields().migrate(context);
                try (var rows = sql.executeQuery("SELECT snapshot_json,owner_name,resume_revision,rule_version FROM resume_assessments")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo(assessment);
                    assertThat(rows.getString(2)).isEqualTo("旧姓名"); assertThat(rows.getLong(3)).isEqualTo(4);
                    assertThat(rows.getString(4)).isEqualTo("old-v1");
                }
                try (var rows = sql.executeQuery("SELECT answered_count,preview FROM interview_sessions")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(1); assertThat(rows.getString(2)).isEqualTo("最后反馈");
                }
                try (var rows = sql.executeQuery("SELECT id,closing_date,closed,salary_value FROM job_postings")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isEqualTo(123);
                    assertThat(rows.getString(2)).isEqualTo("2026-09-30"); assertThat(rows.getBoolean(3)).isTrue(); assertThat(rows.getDouble(4)).isEqualTo(15);
                }
                try (var rows = sql.executeQuery("SELECT rule_version,report_json FROM career_reports")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isNull(); assertThat(rows.getString(2)).isEqualTo("{}");
                }
            } finally {
                // 显式删除临时表，即使测试准备失败也不会删除持久表。
                for (String table : created) sql.execute("DROP TEMPORARY TABLE IF EXISTS " + table);
            }
        }
    }
}
