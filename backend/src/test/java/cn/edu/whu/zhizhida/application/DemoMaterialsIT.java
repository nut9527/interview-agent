package cn.edu.whu.zhizhida.application;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import cn.edu.whu.zhizhida.resume.api.ResumeUserData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class DemoMaterialsIT extends IntegrationTestBase {
    @Autowired JdbcTemplate jdbc;
    @Autowired ResumeUserData resumeData;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void presetsCanBeEditedAnalyzedAndUsedToStartInterviews() throws Exception {
        String token = demoToken();
        var resumes = data(performGet("/api/resumes", token));
        var jobs = data(performGet("/api/target-jobs", token));
        assertThat(resumes.size()).isEqualTo(5);
        assertThat(jobs.path("total").asInt()).isEqualTo(4);
        var kinds = new HashSet<String>();
        for (var summary : jobs.path("items")) {
            String jobId = summary.path("id").asText();
            var job = data(performGet("/api/target-jobs/" + jobId, token));
            assertThat(job.path("jdText").asText()).contains("非真实招聘", "岗位职责", "任职要求", "加分项");
            assertThat(job.path("requirements").size()).isGreaterThan(5);
            for (var requirement : job.path("requirements")) {
                assertThat(job.path("jdText").asText()).contains(requirement.path("sourceText").asText());
                kinds.add(requirement.path("kind").asText());
            }
            String role = job.path("title").asText().split("｜")[0];
            JsonNode matchingResume = null;
            for (var resume : resumes) {
                if (resume.path("targetRole").asText().equals(role)
                        && resume.path("ownerName").asText().contains("（演示）")) matchingResume = resume;
            }
            assertThat(matchingResume).as(role + " 应有配套简历").isNotNull();
            long resumeId = matchingResume.path("id").asLong();
            assertThat(matchingResume.path("details").path("projects").size()).isPositive();
            assertThat(matchingResume.path("details").path("education").size()).isPositive();
            assertThat(matchingResume.path("details").path("contact").path("email").asText()).endsWith("@example.com");
            var versions = data(performGet("/api/resumes/" + resumeId + "/versions", token));
            assertThat(versions.path("total").asInt()).isEqualTo(1);
            // 走公开保存接口，确认预置的结构化信息满足表单校验，并生成可比较的新版本。
            var edited = data(performPut("/api/resumes/" + resumeId, matchingResume, token));
            assertThat(edited.path("revision").asLong()).isEqualTo(1);
            assertThat(edited.path("details")).isEqualTo(matchingResume.path("details"));
            var analysis = data(performPost("/api/target-jobs/" + jobId + "/analysis",
                    Map.of("resumeId", resumeId, "revision", 0), token));
            assertThat(analysis.path("requirements").valueStream().anyMatch(
                    match -> match.path("status").asText().equals("REFERENCED"))).isTrue();
            assertThat(analysis.path("requirements").valueStream().anyMatch(
                    match -> !match.path("unreferencedKeywords").isEmpty())).isTrue();
            var session = data(startInterview(Map.of(
                    "targetRole", role, "resumeId", resumeId, "targetJobId", jobId,
                    "targetJobRevision", 0, "totalRounds", 1), token));
            assertThat(session.path("resumeId").asLong()).isEqualTo(resumeId);
            assertThat(session.path("jobSnapshot").asText()).contains("非真实招聘");
            assertThat(session.path("turns").size()).isPositive();
        }
        assertThat(kinds).containsExactlyInAnyOrder("MUST", "BONUS", "UNKNOWN");
    }

    @Test
    void deletingSamplesDoesNotAffectOtherVisitorsOrFutureDemos() throws Exception {
        String first = demoToken(), second = demoToken();
        var firstJobs = data(performGet("/api/target-jobs", first)).path("items");
        var secondJobs = data(performGet("/api/target-jobs", second)).path("items");
        var firstIds = new HashSet<String>();
        firstJobs.forEach(job -> firstIds.add(job.path("id").asText()));
        secondJobs.forEach(job -> assertThat(firstIds).doesNotContain(job.path("id").asText()));
        String jobId = firstJobs.get(0).path("id").asText();
        long resumeId = data(performGet("/api/resumes", first)).get(0).path("id").asLong();
        assertThat(performGet("/api/target-jobs/" + jobId, second).getResponse().getStatus()).isEqualTo(404);
        assertThat(performGet("/api/resumes/" + resumeId, second).getResponse().getStatus()).isEqualTo(404);
        data(performDelete("/api/target-jobs/" + jobId + "?revision=0", first));
        data(performDelete("/api/resumes/" + resumeId, first));
        assertThat(data(performGet("/api/target-jobs", first)).path("total").asInt()).isEqualTo(3);
        assertThat(data(performGet("/api/resumes", first)).size()).isEqualTo(4);
        for (String token : new String[]{second, demoToken()}) {
            assertThat(data(performGet("/api/target-jobs", token)).path("total").asInt()).isEqualTo(4);
            assertThat(data(performGet("/api/resumes", token)).size()).isEqualTo(5);
        }
        String registered = registerUniqueUserToken("password123", "普通用户");
        assertThat(data(performGet("/api/target-jobs", registered)).path("total").asInt()).isZero();
        assertThat(data(performGet("/api/resumes", registered)).size()).isZero();
    }

    @Test
    void existingTemplateGetsMissingSamplesOnlyOnceWithoutReplacingOriginal() throws Exception {
        // 在测试账号上模拟旧版仅有一份自定义简历的模板，避免更改共用模板影响其他用例。
        String token = registerUniqueUserToken("password123", "旧模板测试");
        long owner = data(performGet("/api/users/me", token)).path("id").asLong();
        var original = data(performPost("/api/resumes", Map.of("ownerName", "已有模板",
                "targetRole", "Java", "summary", "保留原有内容"), token));
        var transaction = new TransactionTemplate(transactions);
        transaction.executeWithoutResult(status -> resumeData.initializeDemoTemplate(owner));
        var afterFirst = data(performGet("/api/resumes", token));
        assertThat(afterFirst.size()).isEqualTo(5);
        transaction.executeWithoutResult(status -> resumeData.initializeDemoTemplate(owner));
        assertThat(data(performGet("/api/resumes", token))).isEqualTo(afterFirst);
        assertThat(data(performGet("/api/resumes/" + original.path("id").asLong(), token))).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resume_versions WHERE user_id=?", Long.class, owner)).isEqualTo(5);
    }

    private String demoToken() throws Exception {
        return data(performPost("/api/auth/demo", Map.of())).path("token").asText();
    }

    private JsonNode data(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }
}
