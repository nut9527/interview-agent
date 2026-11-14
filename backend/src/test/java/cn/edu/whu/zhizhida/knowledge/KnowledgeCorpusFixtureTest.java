package cn.edu.whu.zhizhida.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Chunk;
import cn.edu.whu.zhizhida.knowledge.domain.KnowledgeText;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class KnowledgeCorpusFixtureTest {
    @Test
    void annotatedQuotesSurviveProductionChunkingAndCoverCrowdedSections() throws Exception {
        var fixture =
                KnowledgeCorpusFixture.load(new JsonMapper(), KnowledgeCorpusFixture.RESOURCE);
        assertThat(fixture.path("documents").size()).isGreaterThanOrEqualTo(20);
        assertThat(fixture.path("cases").size()).isGreaterThanOrEqualTo(80);
        Map<String, List<Chunk>> chunks = new HashMap<>();
        var crowdedTopics = new HashSet<String>();
        for (var document : fixture.path("documents")) {
            var evidence =
                    KnowledgeText.split(
                                    document.path("id").asText(), document.path("content").asText())
                            .stream()
                            .filter(KnowledgeText::isEvidence)
                            .toList();
            chunks.put(document.path("id").asText(), evidence);
            var counts =
                    evidence.stream()
                            .collect(Collectors.groupingBy(Chunk::heading, Collectors.counting()));
            if (counts.values().stream().anyMatch(count -> count > 2))
                crowdedTopics.add(document.path("topic").asText());
        }
        assertThat(crowdedTopics).isNotEmpty();
        for (var item : fixture.path("cases")) {
            assertThat(item.path("scope").size())
                    .as("Production vector scope limit: %s", item.path("id").asText())
                    .isBetween(1, 5);
            for (var quote : item.path("evidence")) {
                assertThat(chunks.get(quote.path("documentId").asText()))
                        .as("Quote must fit a real evidence chunk: %s", item.path("id").asText())
                        .anyMatch(
                                chunk ->
                                        chunk.heading().equals(quote.path("heading").asText())
                                                && chunk.content()
                                                        .contains(quote.path("quote").asText()));
            }
        }
    }
}
