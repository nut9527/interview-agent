package cn.edu.whu.zhizhida.questionbank;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import cn.edu.whu.zhizhida.common.persistence.JsonColumnCodec;
import cn.edu.whu.zhizhida.questionbank.api.QuestionTopic;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class QuestionBankIntegrationIT extends IntegrationTestBase {
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired QuestionRepository repository;
    @Autowired JsonColumnCodec json;

    @Test void authenticatesAllReadsAndProvidesNoMutationEndpoint() throws Exception {
        for (String path : List.of("/api/questions", "/api/questions/metadata", "/api/questions/qb-java-001")) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
            mockMvc.perform(get(path).header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
        }
        String token = registerUniqueUserToken("password123", "题库权限");
        var first = data("/api/questions/qb-java-001", token);
        String second = registerUniqueUserToken("password123", "题库共享");
        assertThat(data("/api/questions/qb-java-001", second)).isEqualTo(first);
        for (var request : List.of(post("/api/questions"), put("/api/questions/qb-java-001"), delete("/api/questions/qb-java-001"))) {
            mockMvc.perform(request.header("Authorization", "Bearer " + token)).andExpect(status().isMethodNotAllowed());
        }
    }
    @Test void filtersSearchesAndPaginatesWithSummaryProjection() throws Exception {
        String token = registerUniqueUserToken("password123", "题库列表");
        var first = data("/api/questions?size=7", token);
        var second = data("/api/questions?page=2&size=7", token);
        assertThat(first.path("total").asInt()).isEqualTo(36);
        assertThat(first.path("items").size()).isEqualTo(7);
        var ids = new ArrayList<String>();
        first.path("items").forEach(row -> {
            ids.add(row.path("id").asText());
            assertThat(row.has("referenceAnswer")).isFalse();
            assertThat(row.has("question")).isFalse();
            assertThat(row.path("questionSummary").asText().length()).isLessThanOrEqualTo(160);
        });
        second.path("items").forEach(row -> ids.add(row.path("id").asText()));
        assertThat(ids).doesNotHaveDuplicates().isSorted();
        assertThat(data("/api/questions?page=100&size=12", token).path("items").isEmpty()).isTrue();
        for (var topic : QuestionTopic.values()) {
            assertThat(data("/api/questions?topic=" + topic, token).path("total").asInt()).isEqualTo(6);
            assertThat(data("/api/questions?topic=" + topic + "&difficulty=STANDARD", token).path("total").asInt()).isEqualTo(2);
        }
        assertThat(data("/api/questions?difficulty=ADVANCED", token).path("total").asInt()).isEqualTo(12);
        assertThat(data("/api/questions?topic=JAVA&difficulty=BEGINNER&keyword=HashMap", token).path("total").asInt()).isEqualTo(1);
        // 该词仅出现在题目正文中，用于验证检索不局限于标题。
        assertThat(data("/api/questions?keyword=双击", token).path("items").get(0).path("id").asText()).isEqualTo("qb-database-003");
        assertThat(data("/api/questions?keyword=不可能命中的词", token).path("total").asInt()).isZero();
        assertThat(data("/api/questions?keyword=_", token).path("total").asInt()).isEqualTo(1);
        assertThat(data("/api/questions?keyword=%25", token).path("total").asInt()).isEqualTo(2);
        assertThat(data("/api/questions?keyword=%27%20OR%201=1", token).path("total").asInt()).isZero();
        assertThat(data("/api/questions?keyword=%20%20", token).path("total").asInt()).isEqualTo(36);
    }
    @Test void invalidQueriesAndMissingIdsUseExistingErrorEnvelope() throws Exception {
        String token = registerUniqueUserToken("password123", "题库参数");
        for (String query : List.of("page=0", "page=1000001", "page=oops", "size=0", "size=101", "topic=UNKNOWN",
                "difficulty=HARD", "keyword=" + "x".repeat(101))) {
            mockMvc.perform(get("/api/questions?" + query).header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        mockMvc.perform(get("/api/questions/missing").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
    @Test void migrationSeedsCompleteOriginalContentExactlyOnce() throws Exception {
        String token = registerUniqueUserToken("password123", "题库内容");
        var metadata = data("/api/questions/metadata", token);
        assertThat(metadata.path("topics").size()).isEqualTo(6);
        assertThat(metadata.path("difficulties").size()).isEqualTo(3);
        var titles = new HashSet<String>();
        for (var topic : QuestionTopic.values()) for (int i = 1; i <= 6; i++) {
            var detail = data("/api/questions/qb-" + topic.name().toLowerCase(Locale.ROOT) + "-" + String.format("%03d", i), token);
            assertThat(titles.add(detail.path("title").asText())).isTrue();
            assertThat(detail.path("topic").asText()).isEqualTo(topic.name());
            assertThat(detail.path("competencyId").asText()).isEqualTo(topic.competencyId());
            assertThat(detail.path("tags").isArray()).isTrue();
            assertThat(detail.path("keyPoints").size()).isGreaterThanOrEqualTo(3);
            assertThat(detail.path("commonMistakes").size()).isGreaterThanOrEqualTo(2);
            assertThat(detail.path("followUps").size()).isGreaterThanOrEqualTo(2);
            assertThat(detail.path("referenceAnswer").asText().length()).isGreaterThan(100);
            assertThat(detail.path("sourceType").asText()).isEqualTo("ORIGINAL");
            assertThat(detail.path("sourceUrl").isNull()).isTrue();
            assertThat(detail.path("createdAt").asText()).isNotBlank();
        }
        var before = jdbc.queryForList("SELECT * FROM question_bank ORDER BY id");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM question_bank ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='24' AND success=1", Integer.class)).isEqualTo(1);
    }
    @Test @Transactional void jsonFieldsRoundTripWithoutFlatteningOrLosingUnicode() {
        var values = List.of("带引号 \"示例\"", "第二行\n换行、百分号 % 与下划线 _");
        jdbc.update("UPDATE question_bank SET tags_json=?, key_points_json=?, common_mistakes_json=?, follow_ups_json=? WHERE id=?",
                json.write(values), json.write(values), json.write(values), json.write(values), "qb-java-001");
        var value = repository.find("qb-java-001").orElseThrow();
        assertThat(value.tags()).isEqualTo(values);
        assertThat(value.keyPoints()).isEqualTo(values);
        assertThat(value.commonMistakes()).isEqualTo(values);
        assertThat(value.followUps()).isEqualTo(values);
    }
    private JsonNode data(String path, String token) throws Exception {
        // 使用 URI 重载保留已编码的查询参数，避免再次编码百分号。
        var result = mockMvc.perform(get(java.net.URI.create(path)).header("Authorization", "Bearer " + token)
                .characterEncoding(StandardCharsets.UTF_8.name())).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }
}
