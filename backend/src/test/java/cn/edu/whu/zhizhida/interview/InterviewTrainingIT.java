package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class InterviewTrainingIT extends IntegrationTestBase {
    JsonNode data(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    @Test void persistsTopicRetryEvidenceAndOwnerScopedLinks() throws Exception {
        String token = registerAndLoginWithUniqueUsername("password123", "专项复练");
        String base = "/api/interviews/sessions";
        var source = data(performPost(base, Map.of("targetRole", "数据库专项", "trainingTopic", "DATABASE",
                "totalRounds", 6, "difficulty", "ADVANCED"), token));
        String id = source.path("sessionId").asText();
        for (int round = 1; round <= 6; round++) source = data(performPost(base + "/" + id + "/answers",
                Map.of("answer", "我负责数据库事务验证", "requestId", "source-" + round, "expectedTurn", round), token));
        assertThat(source.path("state").asText()).isEqualTo("FINISHED");
        assertThat(data(performGet(base + "/" + id, token))).isEqualTo(source);
        var retry = data(performPost(base, Map.of("targetRole", "客户端标题不可信", "retrySessionId", id,
                "retryRound", 6, "totalRounds", 3, "difficulty", "BEGINNER"), token));
        String retryId = retry.path("sessionId").asText();
        assertThat(retry.path("targetRole").asText()).isEqualTo("数据库专项");
        assertThat(retry.path("difficulty").asText()).isEqualTo("ADVANCED");
        var snapshot = retry.path("agent").path("training").path("retry");
        assertThat(snapshot.path("answer").asText()).isEqualTo("我负责数据库事务验证");
        assertThat(snapshot.path("sourceRound").asInt()).isEqualTo(6);
        assertThat(snapshot.path("competencyId").asText()).isEqualTo("storage");
        assertThat(data(performGet(base + "/" + retryId, token))).isEqualTo(retry);
        var links = data(performGet(base + "/" + id + "/retries", token));
        assertThat(links.path("total").asInt()).isEqualTo(1);
        assertThat(links.path("items").get(0).path("sessionId").asText()).isEqualTo(retryId);
        for (int round = 1; round <= 3; round++) retry = data(performPost(base + "/" + retryId + "/answers",
                Map.of("answer", "我负责索引和事务验证，使用慢查询日志与执行计划进行前后对照。", "requestId", "training-retry-" + round, "expectedTurn", round), token));
        assertThat(retry.path("agent").path("training").path("retry")).isEqualTo(snapshot);
        assertThat(data(performGet(base + "/" + retryId, token))).isEqualTo(retry);
        String other = registerAndLoginWithUniqueUsername("password123", "其他用户");
        assertThat(performGet(base + "/" + id + "/retries", other).getResponse().getStatus()).isEqualTo(404);
        assertThat(performPost(base, Map.of("targetRole", "复练", "retrySessionId", id, "retryRound", 6, "totalRounds", 3), other)
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(performPost(base, Map.of("targetRole", "复练", "retrySessionId", id, "retryRound", 12, "totalRounds", 3), token)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(performPost(base, Map.of("targetRole", "专项", "trainingTopic", "DATABASE", "totalRounds", 7), token)
                .getResponse().getStatus()).isEqualTo(400);
        data(performDelete(base + "/" + id, token));
        assertThat(data(performGet(base + "/" + retryId, token)).path("agent").path("training").path("retry")).isEqualTo(snapshot);
    }
}
