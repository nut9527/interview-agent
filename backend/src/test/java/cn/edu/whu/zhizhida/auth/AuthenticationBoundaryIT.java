package cn.edu.whu.zhizhida.auth;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticationBoundaryIT extends IntegrationTestBase {

    @ParameterizedTest
    @ValueSource(strings = {"/api/users/me", "/api/resumes", "/api/interviews/sessions"})
    void rejectsAnonymousAccessToProtectedEndpoints(String path) throws Exception {
        assertThat(performGet(path).getResponse().getStatus()).isEqualTo(401);
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = "invalid.token.here")
    void rejectsInvalidBearerTokens(String token) throws Exception {
        assertThat(performGet("/api/users/me", token).getResponse().getStatus()).isEqualTo(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/health", "/api/health/ready"})
    void allowsAnonymousHealthChecks(String path) throws Exception {
        assertThat(performGet(path).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void isolatesResumeListsAndRejectsForeignReadsAndDeletes() throws Exception {
        String owner = registerUniqueUserToken("password123", "简历所有者");
        String other = registerUniqueUserToken("password456", "其他用户");
        long ownedId = data(performPost("/api/resumes", Map.of(
                "ownerName", "简历所有者", "targetRole", "Java开发工程师"), owner)).path("id").asLong();
        long otherId = data(performPost("/api/resumes", Map.of(
                "ownerName", "其他用户", "targetRole", "前端开发工程师"), other)).path("id").asLong();

        var ownedList = data(performGet("/api/resumes", owner));
        var otherList = data(performGet("/api/resumes", other));
        assertThat(ownedList).hasSize(1);
        assertThat(otherList).hasSize(1);
        assertThat(ownedList.get(0).path("id").asLong()).isEqualTo(ownedId);
        assertThat(otherList.get(0).path("id").asLong()).isEqualTo(otherId);

        String url = "/api/resumes/" + ownedId;
        assertThat(performGet(url, other).getResponse().getStatus()).isEqualTo(404);
        assertThat(performDelete(url, other).getResponse().getStatus()).isEqualTo(404);
        assertThat(data(performGet(url, owner)).path("id").asLong()).isEqualTo(ownedId);
    }

    private JsonNode data(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        var response = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(response.path("success").asBoolean()).isTrue();
        return response.path("data");
    }
}
