package cn.edu.whu.zhizhida.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.edu.whu.zhizhida.ai.api.AiMode;
import cn.edu.whu.zhizhida.ai.api.JsonChatClient;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Chunk;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Hit;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class KnowledgeAnswererTest {
    @Test
    void missingFactsAbstainEvenWhenKeywordsMatch() throws Exception {
        var fixture = json.readTree(getClass().getResourceAsStream("/knowledge-retrieval.json"));
        when(client.completeJson(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(json.readTree("{\"sufficient\":false,\"claims\":[]}"));
        for (var q : fixture.path("cases"))
            if ("insufficient".equals(q.path("kind").asString())) {
                var answer =
                        new DefaultKnowledgeAnswerer(client, json, AiMode.MODEL)
                                .answer(q.path("question").asString(), hits);
                assertThat(answer.sufficient()).isFalse();
                assertThat(answer.claims()).isEmpty();
                assertThat(answer.message()).contains("资料不足");
            }
    }

    final JsonMapper json = new JsonMapper();
    final JsonChatClient client = mock(JsonChatClient.class);
    final List<Hit> hits =
            List.of(
                    new Hit(
                            "d",
                            "缓存",
                            "笔记",
                            1,
                            new Chunk("d:1", 1, "缓存", 0, 10, 1, 1, "缓存应使用随机过期"),
                            3));

    @Test
    void rejectsUnknownSourcesFabricatedQuotesAndUncitedClaims() {
        for (String citations :
                List.of(
                        "[]",
                        "[{\"chunkId\":\"other\",\"quote\":\"缓存\"}]",
                        "[{\"chunkId\":\"d:1\",\"quote\":\"永不过期\"}]")) {
            when(client.completeJson(anyString(), anyString(), anyDouble(), anyInt()))
                    .thenReturn(
                            json.readTree(
                                    "{\"sufficient\":true,\"claims\":[{\"text\":\"结论\",\"citations\":"
                                            + citations
                                            + "}]}"));
            assertThatThrownBy(
                            () ->
                                    new DefaultKnowledgeAnswerer(client, json, AiMode.MODEL)
                                            .answer("缓存", hits))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void returnsServerOwnedSourceMetadataForExactQuote() {
        when(client.completeJson(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(
                        json.readTree(
                                "{\"sufficient\":true,\"claims\":[{\"text\":\"采用随机过期。\",\"citations\":[{\"chunkId\":\"d:1\",\"quote\":\"随机过期\"}]}]}"));
        var answer = new DefaultKnowledgeAnswerer(client, json, AiMode.MODEL).answer("缓存", hits);
        assertThat(answer.claims().getFirst().citations().getFirst().title()).isEqualTo("缓存");
    }

    @Test
    void synthesisPreservesMultipleSourcesAndTheirVersions() {
        var other =
                new Hit(
                        "other",
                        "另一份笔记",
                        "笔记",
                        3,
                        new Chunk("other:v3:1", 1, "固定过期", 0, 12, 4, 4, "固定过期用于可预测失效"),
                        2);
        when(client.completeJson(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(
                        json.readTree(
                                """
                {"sufficient":true,"claims":[{"text":"两份资料分别建议随机过期和用于可预测失效的固定过期。","citations":[
                {"chunkId":"d:1","quote":"缓存应使用随机过期"},
                {"chunkId":"other:v3:1","quote":"固定过期用于可预测失效"}]}]}
                """));
        var answer =
                new DefaultKnowledgeAnswerer(client, json, AiMode.MODEL)
                        .answer("比较两份笔记中的过期策略与适用边界", List.of(hits.getFirst(), other));
        assertThat(answer.sufficient()).isTrue();
        assertThat(answer.claims()).hasSize(1);
        assertThat(answer.claims().getFirst().citations())
                .extracting(c -> c.documentId())
                .containsExactly("d", "other");
        assertThat(answer.claims().getFirst().citations().getLast().version()).isEqualTo(3);
        assertThat(answer.claims().getFirst().citations().getLast().startLine()).isEqualTo(4);
    }

    @Test
    void oneValidCitationCannotHideAFabricatedSecondSource() {
        when(client.completeJson(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(
                        json.readTree(
                                """
                {"sufficient":true,"claims":[{"text":"A与B不同。","citations":[
                {"chunkId":"d:1","quote":"随机过期"},
                {"chunkId":"d:1","quote":"固定过期只适用于热点数据"}]}]}
                """));
        assertThatThrownBy(
                        () ->
                                new DefaultKnowledgeAnswerer(client, json, AiMode.MODEL)
                                        .answer("比较A与B", hits))
                .isInstanceOf(
                        cn.edu.whu.zhizhida.common.StructuredOutput.FieldValidationException.class);
    }

    @Test
    void conflictingSourcesCanBeReportedWithoutChoosingAWinner() {
        var other =
                new Hit(
                        "other",
                        "另一份笔记",
                        "笔记",
                        2,
                        new Chunk("other:v2:1", 1, "缓存", 0, 10, 2, 2, "缓存不应使用随机过期"),
                        2);
        when(client.completeJson(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(
                        json.readTree(
                                """
                {"sufficient":true,"claims":[{"text":"资料间存在冲突：一份建议随机过期，另一份反对。","citations":[
                {"chunkId":"d:1","quote":"缓存应使用随机过期"},
                {"chunkId":"other:v2:1","quote":"缓存不应使用随机过期"}]}]}
                """));
        var answer =
                new DefaultKnowledgeAnswerer(client, json, AiMode.MODEL)
                        .answer("资料对随机过期的意见是否一致", List.of(hits.getFirst(), other));
        assertThat(answer.claims().getFirst().text()).contains("资料间存在冲突");
        assertThat(answer.claims().getFirst().citations()).hasSize(2);
    }
}
