package cn.edu.whu.zhizhida.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.edu.whu.zhizhida.ai.LangChain4jKnowledgeVectors;
import cn.edu.whu.zhizhida.ai.api.KnowledgeVectors;
import cn.edu.whu.zhizhida.interview.api.KnowledgeSnapshot;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Hit;
import cn.edu.whu.zhizhida.knowledge.domain.KnowledgeText;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;

/** Same frozen cases and production ranker; only vector inclusion and section quota vary. */
class KnowledgeRetrievalAblationTest {
    private static final int LIMIT = 6;
    private record Variant(String id, boolean vector, boolean quota) {}
    private static final List<Variant> VARIANTS = List.of(
            new Variant("baseline", false, false), new Variant("vector_only", true, false),
            new Variant("quota_only", false, true), new Variant("all_enabled", true, true));

    @Test
    @EnabledIfSystemProperty(named = "knowledge.ablation.enabled", matches = "true")
    void evaluateFrozenFixtureWithIndependentSwitches() throws Exception {
        var json = new JsonMapper();
        byte[] fixtureBytes;
        try (var input = getClass().getResourceAsStream("/knowledge-retrieval.json")) {
            fixtureBytes = input.readAllBytes();
        }
        var fixture = json.readTree(fixtureBytes);
        int port = Integer.parseInt(System.getProperty("knowledge.ablation.embeddingPort", "18093"));
        assertThat(port).isBetween(1024, 65535);
        var embedding = OpenAiEmbeddingModel.builder().baseUrl("http://127.0.0.1:" + port + "/v1")
                .modelName("bge-small-zh-v1.5-q").apiKey("local").dimensions(512)
                .maxRetries(0).timeout(Duration.ofSeconds(10)).build();
        var vectors = new LangChain4jKnowledgeVectors(embedding, new InMemoryEmbeddingStore<TextSegment>(),
                "bge-small-zh-v1.5-q:512");
        var snapshots = new ArrayList<KnowledgeSnapshot>();
        var corpus = new ArrayList<Hit>();
        for (var document : fixture.path("documents")) {
            String id = document.path("id").asText(), title = document.path("title").asText();
            String content = document.path("content").asText();
            String hash = sha256(content.getBytes(StandardCharsets.UTF_8));
            snapshots.add(new KnowledgeSnapshot(id, title, "fixture.md", 1, hash, content, List.of()));
            var chunks = KnowledgeText.split(id, 1, content).stream().filter(KnowledgeText::isEvidence).toList();
            chunks.forEach(c -> corpus.add(new Hit(id, title, "fixture.md", 1, c, 0)));
            vectors.index(new KnowledgeVectors.Scope(id, 1, hash), chunks.stream()
                    .map(c -> new KnowledgeVectors.Fragment(c.id(), KnowledgeText.embeddingText(c))).toList());
        }
        var cases = Stream.concat(fixture.path("cases").valueStream(), fixture.path("comparisonCases").valueStream()).toList();
        var details = new ArrayList<Map<String, Object>>();
        var samples = new LinkedHashMap<String, List<KnowledgeRetrievalMetrics.Sample>>();
        VARIANTS.forEach(v -> samples.put(v.id(), new ArrayList<>()));
        int quotaChangedLexical = 0, quotaChangedHybrid = 0;
        var metrics = new SimpleMeterRegistry();
        try {
            for (var item : cases) {
                String query = item.path("question").asText();
                var scope = KnowledgeRetrievalEvaluationTest.strings(item.path("scope"));
                var expected = KnowledgeRetrievalEvaluationTest.strings(item.path("expected"));
                var selected = snapshots.stream().filter(s -> scope.contains(s.id())).toList();
                var candidates = corpus.stream().filter(h -> scope.contains(h.documentId())).toList();
                assertThat(candidates.stream().map(KnowledgeRetrievalEvaluationTest::source)).containsAll(expected);
                var scopes = selected.stream().map(s -> new KnowledgeVectors.Scope(s.id(), s.version(), s.sha256())).toList();
                // Search once per case, then replay the exact same semantic ranking into both vector-on groups.
                // An embedding error fails the test; no fallback is counted as vector evidence.
                var semantic = vectors.search(scopes, query, 30);
                var outputs = new LinkedHashMap<String, Object>();
                var selectedIds = new LinkedHashMap<String, List<String>>();
                for (var variant : VARIANTS) {
                    var replay = replay(variant.vector() ? semantic : List.of(), scopes, query);
                    // At k=6 a quota of 6 is equivalent to quota off, including the refill pass.
                    var settings = new KnowledgeRetrievalSettings(60, 30, 30, variant.quota() ? 2 : LIMIT);
                    var hits = new HybridKnowledgeSearch(replay, metrics, settings).search(selected, candidates, query, LIMIT);
                    assertThat(hits).hasSizeLessThanOrEqualTo(LIMIT)
                            .allMatch(h -> scope.contains(h.documentId()) && KnowledgeText.isEvidence(h.chunk()));
                    var sources = hits.stream().map(KnowledgeRetrievalEvaluationTest::source).toList();
                    var ids = hits.stream().map(h -> h.chunk().id()).toList();
                    selectedIds.put(variant.id(), ids);
                    outputs.put(variant.id(), Map.of("chunkIds", ids, "sources", sources));
                    samples.get(variant.id()).add(new KnowledgeRetrievalMetrics.Sample(item.path("kind").asText(), expected, sources));
                    if (variant.id().equals("baseline")) assertThat(ids).containsExactlyElementsOf(
                            KnowledgeText.rank(candidates, query, LIMIT).stream().map(h -> h.chunk().id()).toList());
                }
                if (!selectedIds.get("baseline").equals(selectedIds.get("quota_only"))) quotaChangedLexical++;
                if (!selectedIds.get("vector_only").equals(selectedIds.get("all_enabled"))) quotaChangedHybrid++;
                details.add(Map.of("id", item.path("id").asText(), "kind", item.path("kind").asText(),
                        "question", query, "scope", scope, "expected", expected,
                        "vectorRanking", semantic, "outputs", outputs));
            }
            assertThat(metrics.find("knowledge.retrieval.degraded").counter()).isNull();
        } finally { metrics.close(); }
        var groups = new LinkedHashMap<String, Object>();
        for (var variant : VARIANTS) groups.put(variant.id(), Map.of(
                "lexicalEnabled", true, "vectorEnabled", variant.vector(), "quotaEnabled", variant.quota(),
                "maxPerHeading", variant.quota() ? 2 : LIMIT,
                "metricsByKind", KnowledgeRetrievalMetrics.byKind(samples.get(variant.id()), List.of(1, 3, LIMIT))));
        var sectionCounts = corpus.stream().collect(Collectors.groupingBy(
                h -> h.documentId() + ":" + h.chunk().heading(), Collectors.counting()));
        var report = new LinkedHashMap<String, Object>();
        report.put("scope", "Offline frozen synthetic corpus; production ranker; actual local BGE embeddings; in-memory vector index; no SQL, pgvector, answer model, latency or human quality claim");
        report.put("fixtureSha256", sha256(fixtureBytes));
        report.put("documents", snapshots.size()); report.put("cases", cases.size()); report.put("k", LIMIT);
        report.put("controls", Map.of("rrfK", 60, "lexicalTopN", 30, "vectorTopN", 30,
                "vectorOnlyMeaning", "Enable vector/RRF enhancement on the unchanged lexical baseline; not pure vector retrieval",
                "sameVectorRankingReplayed", true, "embedding", "bge-small-zh-v1.5-q:512"));
        report.put("corpus", Map.of("evidenceChunks", corpus.size(), "evidenceChunksBySection", sectionCounts,
                "maxEvidenceChunksPerSection", sectionCounts.values().stream().mapToLong(Long::longValue).max().orElseThrow()));
        report.put("groups", groups);
        report.put("pairedEffects", Map.of(
                "quotaWithVector", delta(samples.get("vector_only"), samples.get("all_enabled"), quotaChangedHybrid),
                "quotaWithoutVector", delta(samples.get("baseline"), samples.get("quota_only"), quotaChangedLexical),
                "vectorWithoutQuota", delta(samples.get("baseline"), samples.get("vector_only"), -1),
                "vectorWithQuota", delta(samples.get("quota_only"), samples.get("all_enabled"), -1)));
        report.put("details", details);
        Files.writeString(Path.of(System.getProperty("knowledge.ablation.report", "target/retrieval-ablation.json")),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
    }

    private static Map<String, Object> delta(List<KnowledgeRetrievalMetrics.Sample> before,
            List<KnowledgeRetrievalMetrics.Sample> after, int changed) {
        double recall = 0, mrr = 0, ndcg = 0; int positive = 0;
        for (int i = 0; i < before.size(); i++) {
            if (before.get(i).expected().isEmpty()) continue;
            var a = KnowledgeRetrievalMetrics.at(before.get(i).expected(), before.get(i).retrieved(), LIMIT);
            var b = KnowledgeRetrievalMetrics.at(after.get(i).expected(), after.get(i).retrieved(), LIMIT);
            recall += b.recall() - a.recall(); mrr += b.reciprocalRank() - a.reciprocalRank(); ndcg += b.ndcg() - a.ndcg(); positive++;
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("positivePairs", positive); result.put("recallAt6Delta", recall / positive);
        result.put("mrrAt6Delta", mrr / positive); result.put("ndcgAt6Delta", ndcg / positive);
        if (changed >= 0) result.put("changedRankingsAmongAllCases", changed);
        return result;
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static KnowledgeVectors replay(List<KnowledgeVectors.Match> matches, List<KnowledgeVectors.Scope> scopes, String query) {
        return new KnowledgeVectors() {
            public String fingerprint() { return "frozen-local-bge-ranking"; }
            public void index(Scope scope, List<Fragment> fragments) { throw new UnsupportedOperationException(); }
            public void remove(Scope scope, String fingerprint) { throw new UnsupportedOperationException(); }
            public List<Match> search(List<Scope> actual, String text, int limit) {
                assertThat(actual).containsExactlyElementsOf(scopes);
                assertThat(text).isEqualTo(query); assertThat(limit).isEqualTo(30);
                return matches;
            }
        };
    }
}
