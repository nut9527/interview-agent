package cn.edu.whu.zhizhida;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class ListProjectionMigrationIT extends IntegrationTestBase {
    @Autowired DataSource dataSource;

    @Test void historicalInterviewRowsKeepTheirTranscriptWhenProjectionsAreBackfilled() throws Exception {
        // 仅重放仍在用的面试投影；原迁移中的退役业务表已由 V49 删除。
        String migration = new ClassPathResource("db/migration/V16__list_projections.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        int start = migration.indexOf("ALTER TABLE interview_sessions");
        int end = migration.indexOf("ALTER TABLE ", start + 1);
        assertThat(start).isNotNegative();
        assertThat(end).isGreaterThan(start);
        var script = new ByteArrayResource(migration.substring(start, end).getBytes(StandardCharsets.UTF_8));
        try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
            // 连接级临时表遮蔽同名测试表，清理不会触及持久化业务表。
            try {
                sql.execute("CREATE TEMPORARY TABLE migration_copy LIKE interview_sessions");
                sql.execute("CREATE TEMPORARY TABLE interview_sessions LIKE migration_copy");
                sql.execute("ALTER TABLE interview_sessions DROP COLUMN answered_count,DROP COLUMN preview,DROP INDEX idx_interview_page");
                String transcript = "[{\"speaker\":\"interviewer\",\"content\":\"旧问题\"},{\"speaker\":\"user\",\"content\":\"旧回答\"},{\"speaker\":\"interviewer\",\"content\":\"最后反馈\"}]";
                try (var insert = connection.prepareStatement("INSERT INTO interview_sessions(session_id,user_id,target_role,state,turn_count,turns_json) VALUES ('legacy',1,'Java','FINISHED',2,?)")) {
                    insert.setString(1, transcript);
                    insert.executeUpdate();
                }
                ScriptUtils.executeSqlScript(connection, script);
                try (var rows = sql.executeQuery("SELECT answered_count,preview,turns_json FROM interview_sessions")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isEqualTo(1);
                    assertThat(rows.getString(2)).isEqualTo("最后反馈");
                    assertThat(objectMapper.readTree(rows.getString(3))).isEqualTo(objectMapper.readTree(transcript));
                }
            } finally {
                sql.execute("DROP TEMPORARY TABLE IF EXISTS interview_sessions");
                sql.execute("DROP TEMPORARY TABLE IF EXISTS migration_copy");
            }
        }
    }
}
