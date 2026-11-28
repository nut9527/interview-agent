package cn.edu.whu.zhizhida.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

class ReferenceLibraryIT extends IntegrationTestBase {
    @Test
    void importsOriginalReferenceIntoTheCurrentUsersVersionedKnowledge() throws Exception {
        String owner = registerUniqueUserToken("password123", "资料使用者");
        String other = registerUniqueUserToken("password123", "另一个账号");
        assertThat(performGet("/api/knowledge/library").getResponse().getStatus()).isEqualTo(401);
        var catalog = data(performGet("/api/knowledge/library", owner));
        assertThat(catalog.size()).isEqualTo(25);
        var entry = catalog.get(0);
        String base = "/api/knowledge/library/" + entry.path("id").asString();
        var preview = data(performGet(base, owner));
        String content = preview.path("content").asString() + "\n\n## 我的阅读记录\n\n已核对召回与生成的区别。";
        var imported =
                data(
                        performPost(
                                base + "/import",
                                Map.of(
                                        "title",
                                        "核对后的阅读资料",
                                        "sourceName",
                                        "客户端来源",
                                        "content",
                                        content,
                                        "confirmed",
                                        true),
                                owner));
        assertThat(imported.path("content").asString()).isEqualTo(content);
        assertThat(imported.path("sourceName").asString())
                .isEqualTo(entry.path("sourceName").asString());
        assertThat(imported.path("chunks").size()).isGreaterThan(1);
        String path = "/api/knowledge/documents/" + imported.path("id").asString();
        var archived = data(performGet(path + "/versions/1", owner));
        assertThat(archived.path("content").asString()).isEqualTo(content);
        assertThat(performGet(path, other).getResponse().getStatus()).isEqualTo(404);
    }

    private JsonNode data(MvcResult response) throws Exception {
        String body = response.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getResponse().getStatus()).as(body).isEqualTo(200);
        return objectMapper.readTree(body).path("data");
    }
}
