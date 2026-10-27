package cn.edu.whu.zhizhida.jobs;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class TargetJobIntegrationIT extends IntegrationTestBase {
    @Autowired JdbcTemplate jdbc;
    private static final String JD = "任职要求：\n熟悉 Java 和 MySQL\n本科及以上\n加分项：\n熟悉 Docker\n岗位职责：\n负责跨团队沟通";

    @Test void fullWorkflowPersistsCorrectionsAndEvidenceWithoutPollutingCatalog() throws Exception {
        String token = registerAndLoginWithUniqueUsername("password123", "目标岗位测试");
        long catalogCount = jdbc.queryForObject("SELECT COUNT(*) FROM job_postings", Long.class);
        JsonNode extraction = data(performPost("/api/target-jobs/extract", Map.of("jdText", JD), token));
        var input = input(); input.put("requirements", extraction.path("requirements"));
        JsonNode created = data(performPost("/api/target-jobs", input, token));
        String url = "/api/target-jobs/" + created.path("id").asText();
        assertThat(data(performGet(url, token))).isEqualTo(created);
        JsonNode list = data(performGet("/api/target-jobs?page=1&size=1", token));
        assertThat(list.path("total").asInt()).isEqualTo(1);
        assertThat(list.path("items").get(0).has("jdText")).isFalse();
        assertThat(data(performGet("/api/target-jobs?page=2&size=1", token)).path("items").isEmpty()).isTrue();
        var corrected = objectMapper.convertValue(created.path("requirements"), List.class);
        ((Map<String, Object>) corrected.get(2)).put("kind", "MUST");
        ((Map<String, Object>) corrected.get(2)).put("text", "Docker 部署实践（已确认）");
        input.put("requirements", corrected);
        JsonNode updated = data(performPut(url, input, token));
        assertThat(updated.path("revision").asLong()).isEqualTo(1);
        assertThat(data(performGet(url, token)).path("requirements").get(2).path("kind").asText()).isEqualTo("MUST");
        assertThat(performPut(url, input, token).getResponse().getStatus()).isEqualTo(409);
        assertThat(performDelete(url + "?revision=0", token).getResponse().getStatus()).isEqualTo(409);
        long resumeId = data(performPost("/api/resumes", Map.of("ownerName", "测试", "targetRole", "Java", "skills", List.of("Java"),
                "projectExperiences", List.of("使用 Java 和 MySQL 实现岗位服务")), token)).path("id").asLong();
        var analysis = data(performPost(url + "/analysis", Map.of("resumeId", resumeId, "revision", 1), token));
        assertThat(analysis.path("requirements").get(0).path("evidence").toString()).contains("projectExperiences[0]", "岗位服务");
        assertThat(analysis.path("requirements").get(2).path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(performPost(url + "/analysis", Map.of("resumeId", resumeId, "revision", 0), token).getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job_postings", Long.class)).isEqualTo(catalogCount);
        data(performDelete(url + "?revision=1", token));
        assertThat(performGet(url, token).getResponse().getStatus()).isEqualTo(404);
    }
    @Test void enforcesIdentityForEveryPrivateOperationAndResumeReference() throws Exception {
        String owner = registerAndLoginWithUniqueUsername("password123", "甲");
        String other = registerAndLoginWithUniqueUsername("password123", "乙");
        String url = "/api/target-jobs/" + data(performPost("/api/target-jobs", input(), owner)).path("id").asText();
        long resume = data(performPost("/api/resumes", Map.of("ownerName", "乙", "targetRole", "后端"), other)).path("id").asLong();
        assertThat(performGet("/api/target-jobs").getResponse().getStatus()).isEqualTo(401);
        assertThat(performPost("/api/target-jobs/extract", Map.of("jdText", JD)).getResponse().getStatus()).isEqualTo(401);
        assertThat(data(performGet("/api/target-jobs", other)).path("total").asInt()).isZero();
        assertThat(performGet(url, other).getResponse().getStatus()).isEqualTo(404);
        assertThat(performPut(url, input(), other).getResponse().getStatus()).isEqualTo(404);
        assertThat(performDelete(url + "?revision=0", other).getResponse().getStatus()).isEqualTo(404);
        assertThat(performPost(url + "/analysis", Map.of("resumeId", resume, "revision", 0), other).getResponse().getStatus()).isEqualTo(404);
        assertThat(performPost(url + "/analysis", Map.of("resumeId", resume, "revision", 0), owner).getResponse().getStatus()).isEqualTo(404);
        assertThat(data(performGet(url, owner)).path("revision").asInt()).isZero();
    }
    @Test void simultaneousEditsOnlyCommitOneRevision() throws Exception {
        String token = registerAndLoginWithUniqueUsername("password123", "并发目标岗位");
        String url = "/api/target-jobs/" + data(performPost("/api/target-jobs", input(), token)).path("id").asText();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Integer> edit = () -> { start.await(); return performPut(url, input(), token).getResponse().getStatus(); };
            var first = executor.submit(edit); var second = executor.submit(edit); start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
            assertThat(data(performGet(url, token)).path("revision").asLong()).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }
    @Test void validatesDatesAndNestedLimitsWithoutWriting() throws Exception {
        String token = registerAndLoginWithUniqueUsername("password123", "目标校验");
        var invalid = input(); invalid.put("deadline", "2026-02-30");
        assertThat(performPost("/api/target-jobs", invalid, token).getResponse().getStatus()).isEqualTo(400);
        invalid = input(); invalid.put("sourceUrl", "javascript:alert(1)");
        assertThat(performPost("/api/target-jobs", invalid, token).getResponse().getStatus()).isEqualTo(400);
        invalid = input(); invalid.put("requirements", Collections.nCopies(101, Map.of("id", "r", "text", "x", "kind", "MUST", "keywords", List.of())));
        assertThat(performPost("/api/target-jobs", invalid, token).getResponse().getStatus()).isEqualTo(400);
        assertThat(data(performGet("/api/target-jobs", token)).path("total").asInt()).isZero();
        var nullable = input(); nullable.put("deadline", null); nullable.put("company", null); nullable.put("sourceName", null); nullable.put("sourceUrl", null);
        var saved = data(performPost("/api/target-jobs", nullable, token));
        assertThat(saved.path("deadline").isNull()).isTrue();
        assertThat(saved.path("company").asText()).isEmpty();
    }
    private Map<String, Object> input() {
        var input = new LinkedHashMap<String, Object>();
        input.put("revision", 0); input.put("title", "Java 实习生"); input.put("company", "示例公司"); input.put("sourceName", "官网");
        input.put("sourceUrl", "https://example.test/jobs/1"); input.put("deadline", "2026-11-01"); input.put("jdText", JD); input.put("requirements", List.of()); return input;
    }
    private JsonNode data(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }
}
