package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class InterviewAgentWorkflowIT extends IntegrationTestBase {
    private JsonNode data(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    @Test void persistsTwelveRoundPlanAndEvaluationsWithHistoricalReplayAndOwnerIsolation() throws Exception {
        String token = registerAndLoginWithUniqueUsername("password123", "Agent训练");
        var session = data(performPost("/api/interviews/sessions", Map.of("targetRole", "Java 后端",
                "mode", "BACKEND_BASICS", "totalRounds", 12, "jobDescription", "Java、MySQL，关注接口设计与验证"), token));
        assertThat(session.path("agent").path("plan")).hasSize(8);
        assertThat(session.path("agent").path("executionMode").asText()).isEqualTo("mock");
        String url = "/api/interviews/sessions/" + session.path("sessionId").asText();
        JsonNode first = null;
        for (int round = 1; round <= 12; round++) {
            session = data(performPost(url + "/answers", Map.of("answer", "我负责接口设计", "requestId", "agent-answer-" + round, "expectedTurn", round), token));
            if (round == 1) first = session;
            assertThat(data(performGet(url, token))).isEqualTo(session);
        }
        assertThat(session.path("state").asText()).isEqualTo("FINISHED");
        assertThat(session.path("agent").path("evaluations")).hasSize(12);
        assertThat(session.path("agent").path("digest")).hasSize(12);
        var replay = data(performPost(url + "/answers", Map.of("answer", "我负责接口设计", "requestId", "agent-answer-1", "expectedTurn", 1), token));
        assertThat(replay).isEqualTo(first);
        assertThat(data(performGet(url, token))).isEqualTo(session);
        String other = registerAndLoginWithUniqueUsername("password123", "其他用户");
        assertThat(performGet(url, other).getResponse().getStatus()).isEqualTo(404);
        var request = Map.of("targetRole", "Java 后端", "mode", "BACKEND_BASICS", "totalRounds", 12);
        var next = data(performPost("/api/interviews/sessions", request, token));
        assertThat(next.path("agent").path("practiceMemory")).isNotEmpty();
        assertThat(data(performPost("/api/interviews/sessions", request, other)).path("agent").path("practiceMemory")).isEmpty();
        data(performDelete(url, token));
        assertThat(data(performPost("/api/interviews/sessions", request, token)).path("agent").path("practiceMemory")).isEmpty();
    }
}
