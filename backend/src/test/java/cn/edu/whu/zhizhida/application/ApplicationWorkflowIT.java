package cn.edu.whu.zhizhida.application;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import cn.edu.whu.zhizhida.auth.SessionRepository;
import cn.edu.whu.zhizhida.auth.UserAccountRepository;
import cn.edu.whu.zhizhida.resume.DefaultResumeUserData;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.util.AopTestUtils;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 通过真实 MySQL 故障验证 Spring 代理与外层业务流程事务。 */
class ApplicationWorkflowIT extends IntegrationTestBase {
    private static final List<String> TABLES = List.of("app_users", "app_sessions", "resumes", "resume_versions",
            "resume_assessments", "career_reports", "interview_sessions", "user_favorite_jobs", "user_target_jobs",
            "tracked_applications", "application_events", "application_commands");
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoCleanupWorker cleanup;
    @MockitoSpyBean UserAccountRepository accounts;
    @MockitoSpyBean SessionRepository sessions;
    @MockitoSpyBean DefaultResumeUserData resumeData;

    @Test
    void accountClosureFailureRestoresEveryModuleAndSessionThenCanBeRetried() throws Exception {
        String owner = registerAndLoginWithUniqueUsername("password123", "注销事务");
        String other = registerAndLoginWithUniqueUsername("password123", "保留用户");
        long ownerId = populate(owner), otherId = populate(other);
        var before = counts(ownerId);
        var others = counts(otherId);
        assertThat(before).containsEntry("resumes", 1L).containsEntry("resume_versions", 1L)
                .containsEntry("interview_sessions", 1L).containsEntry("user_target_jobs", 1L).containsEntry("career_reports", 1L);
        assertThat(performDeleteWithBody("/api/users/me", Map.of("currentPassword", "incorrect"), owner)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(counts(ownerId)).isEqualTo(before);

        doThrow(new DataAccessResourceFailureException("injected identity deletion failure"))
                .when(accounts).deleteById(ownerId);
        assertThat(performDeleteWithBody("/api/users/me", Map.of("currentPassword", "password123"), owner)
                .getResponse().getStatus()).isEqualTo(500);
        assertThat(counts(ownerId)).isEqualTo(before);
        assertThat(counts(otherId)).isEqualTo(others);
        assertThat(performGet("/api/users/me", owner).getResponse().getStatus()).isEqualTo(200);

        reset(accounts);
        assertThat(performDeleteWithBody("/api/users/me", Map.of("currentPassword", "password123"), owner)
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(counts(ownerId).values()).allMatch(count -> count == 0);
        assertThat(counts(otherId)).isEqualTo(others);
        assertThat(performGet("/api/users/me", owner).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void demoSessionFailureRollsBackAccountResumeVersionsAndFavorites() throws Exception {
        long templateId = jdbc.queryForObject("SELECT id FROM app_users WHERE account_type = 'DEMO_TEMPLATE'", Long.class);
        var before = counts(null);
        doThrow(new DataAccessResourceFailureException("injected session failure"))
                .when(sessions).create(anyString(), anyLong(), any());
        assertThat(performPost("/api/auth/demo", Map.of()).getResponse().getStatus()).isEqualTo(500);
        assertThat(counts(null)).isEqualTo(before);
        reset(sessions);
        var demo = data(performPost("/api/auth/demo", Map.of()));
        long userId = demo.path("user").path("id").asLong();
        assertThat(demo.path("user").path("demo").asBoolean()).isTrue();
        assertThat(demo.path("user").path("username").asText()).isEqualTo("demo");
        assertThat(counts(userId)).containsEntry("app_users", 1L).containsEntry("app_sessions", 1L);
        assertThat(counts(userId).get("resumes")).isEqualTo(counts(templateId).get("resumes")).isPositive();
        assertThat(counts(userId).get("resume_versions")).isEqualTo(counts(userId).get("resumes"));
    }

    @Test
    void cleanupFailureRollsBackEarlierModulesAndKeepsLiveDemoAndTemplate() throws Exception {
        var expired = data(performPost("/api/auth/demo", Map.of()));
        var live = data(performPost("/api/auth/demo", Map.of()));
        long expiredId = populate(expired.path("token").asText());
        long liveId = live.path("user").path("id").asLong();
        long templateId = jdbc.queryForObject("SELECT id FROM app_users WHERE account_type = 'DEMO_TEMPLATE'", Long.class);
        var before = counts(expiredId);
        var liveBefore = counts(liveId);
        var templateBefore = counts(templateId);
        jdbc.update("UPDATE app_users SET demo_expires_at = '1971-01-01 00:00:00' WHERE id = ?", expiredId);
        DefaultResumeUserData resumeTarget = AopTestUtils.getUltimateTargetObject(resumeData);
        doThrow(new DataAccessResourceFailureException("injected resume purge failure"))
                .when(resumeTarget).deleteOwnedData(expiredId);
        assertThatThrownBy(() -> cleanup.runBatch()).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(counts(expiredId)).isEqualTo(before);
        reset(resumeTarget);
        assertThat(cleanup.runBatch()).isGreaterThanOrEqualTo(1);
        assertThat(counts(expiredId).values()).allMatch(count -> count == 0);
        assertThat(counts(liveId)).isEqualTo(liveBefore);
        assertThat(counts(templateId)).isEqualTo(templateBefore);
    }

    private long populate(String token) throws Exception {
        long userId = data(performGet("/api/users/me", token)).path("id").asLong();
        var resume = data(performPost("/api/resumes", Map.of("ownerName", "事务测试", "targetRole", "Java 后端实习",
                "skills", List.of("Java"), "educationExperiences", List.of("本科"),
                "projectExperiences", List.of("使用 Java 实现目录接口"), "internshipExperiences", List.of(),
                "certificates", List.of()), token));
        data(performPost("/api/interviews/sessions", Map.of("targetRole", "Java 后端", "resumeId", resume.path("id").asLong()), token));
        var target = data(performPost("/api/target-jobs", Map.of("revision", 0, "title", "私有岗位", "jdText", "必须 Java", "requirements", List.of()), token));
        jdbc.update("INSERT INTO career_reports(report_id,user_id,owner_name,target_role,total_score,readiness_level,report_json) VALUES (?,?,?, ?,0,'LEGACY','{}')",
                java.util.UUID.randomUUID().toString(), userId, "旧姓名", "旧岗位");
        return userId;
    }

    private Map<String, Long> counts(Long userId) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String table : TABLES) {
            String where = userId == null ? "" : " WHERE " + (table.equals("app_users") ? "id" : "user_id") + " = ?";
            result.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table + where, Long.class,
                    userId == null ? new Object[0] : new Object[]{userId}));
        }
        return result;
    }

    private JsonNode data(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }
}
