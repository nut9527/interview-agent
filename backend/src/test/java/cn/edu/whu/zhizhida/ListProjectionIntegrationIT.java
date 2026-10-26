package cn.edu.whu.zhizhida;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ListProjectionIntegrationIT extends IntegrationTestBase {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    private JsonNode data(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private Map<String, Object> resume() {
        return new LinkedHashMap<>(Map.of("ownerName", "历史姓名", "targetRole", "Java 后端实习", "skills", List.of("Java"),
                "educationExperiences", List.of(), "projectExperiences", List.of("使用 Java 实现接口"),
                "internshipExperiences", List.of(), "certificates", List.of()));
    }



    @Test void reportVersionAndInterviewListsExposeSummariesAndOwnedDetails() throws Exception {
        String token = registerAndLoginWithUniqueUsername("password123", "历史用户");
        String other = registerAndLoginWithUniqueUsername("password123", "历史隔离用户");
        var saved = data(performPost("/api/resumes", resume(), token));
        String versions = "/api/resumes/" + saved.path("id").asLong() + "/versions";
        var version = data(performGet(versions + "?size=1", token)).path("items").get(0);
        assertThat(version.has("snapshot")).isFalse();
        assertThat(data(performGet(versions + "/" + version.path("id").asLong(), token)).path("snapshot")).isEqualTo(saved);
        assertThat(performGet(versions, other).getResponse().getStatus()).isEqualTo(404);
        assertThat(performGet(versions + "/" + version.path("id").asLong(), other).getResponse().getStatus()).isEqualTo(404);
        var session = data(performPost("/api/interviews/sessions", Map.of("targetRole", "Java 后端", "resumeId", saved.path("id").asLong()), token));
        String id = session.path("sessionId").asText();
        data(performPost("/api/interviews/sessions/" + id + "/answers", Map.of("answer", "使用 Java 编写接口并用 JUnit 验证结果", "requestId", "page-test", "expectedTurn", 1), token));
        var history = data(performGet("/api/interviews/sessions", token)).path("items").get(0);
        assertThat(history.path("answeredCount").asInt()).isEqualTo(1);
        assertThat(history.path("preview").asText()).isNotBlank();
        assertThat(history.has("turns") || history.has("resumeSnapshot") || history.has("jobSnapshot")).isFalse();
        assertThat(data(performGet("/api/interviews/sessions/" + id, token)).path("resumeRevision")).isEqualTo(saved.path("revision"));
        assertThat(data(performGet("/api/interviews/sessions", other)).path("total").asInt()).isZero();
    }




}
