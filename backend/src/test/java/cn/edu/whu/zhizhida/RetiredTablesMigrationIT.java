package cn.edu.whu.zhizhida;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/** 在受保护的测试库中验证完整历史迁移链与退役后的实际表结构。 */
class RetiredTablesMigrationIT extends IntegrationTestBase {
    private static final List<String> RETIRED_TABLES = List.of(
            "job_postings", "user_favorite_jobs", "job_catalog_state", "job_refresh_runs",
            "job_refresh_tasks", "job_refresh_attempts", "tracked_applications", "application_events",
            "application_commands", "resume_assessments", "career_reports", "agent_runs",
            "agent_steps", "agent_events", "agent_commands", "agent_dispatch_lock");

    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    @Test void validatesHistoryAndRetiresOnlyUnusedTables() {
        flyway.validate();
        var tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_type='BASE TABLE'", String.class);
        assertThat(tables).doesNotContainAnyElementsOf(RETIRED_TABLES)
                .contains("app_users", "app_sessions", "resumes", "resume_versions", "user_target_jobs",
                        "interview_sessions", "interview_runs", "question_bank", "knowledge_documents");
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success=1", String.class))
                .contains("1", "17", "48", "49");
        assertThat(jdbc.queryForObject("SELECT @@FOREIGN_KEY_CHECKS", Integer.class)).isEqualTo(1);
    }
}
