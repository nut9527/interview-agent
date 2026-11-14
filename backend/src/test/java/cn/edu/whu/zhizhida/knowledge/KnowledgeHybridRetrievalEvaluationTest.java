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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 用固定片段和来源标注比较训练检索；显式指定本机嵌入模型时才调用嵌入服务。 */
class KnowledgeHybridRetrievalEvaluationTest {
    private static final int LIMIT = 6;

    private record Result(
            String id,
            String kind,
            List<String> expected,
            List<String> retrieved,
            long micros,
            String split) {}

    @Test
    void evaluateTrainingRetrieval() throws Exception {
        var json = new JsonMapper();
        String resource = System.getProperty("knowledge.eval.fixture", "/knowledge-retrieval.json");
        JsonNode fixture = KnowledgeCorpusFixture.load(json, resource);
        String model = System.getProperty("knowledge.eval.embeddingModel");
        var snapshots = new ArrayList<KnowledgeSnapshot>();
        var corpus = new ArrayList<Hit>();
        var vectors = model == null ? null : localVectors(model);
        for (var document : fixture.path("documents")) {
            String id = document.path("id").asText();
            String title = document.path("title").asText();
            String content = document.path("content").asText();
            String sourceName = document.path("file").asText("fixture.md");
            String hash =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(content.getBytes(StandardCharsets.UTF_8)));
            snapshots.add(
                    new KnowledgeSnapshot(id, title, sourceName, 1, hash, content, List.of()));
            var chunks = KnowledgeText.split(id, 1, content);
            for (var chunk : chunks) corpus.add(new Hit(id, title, sourceName, 1, chunk, 0));
            if (vectors != null) {
                var fragments =
                        chunks.stream()
                                .filter(KnowledgeText::isEvidence)
                                .map(
                                        c ->
                                                new KnowledgeVectors.Fragment(
                                                        c.id(), KnowledgeText.embeddingText(c)))
                                .toList();
                if (!fragments.isEmpty())
                    vectors.index(new KnowledgeVectors.Scope(id, 1, hash), fragments);
            }
        }

        var cases =
                Stream.concat(
                                fixture.path("cases").valueStream(),
                                fixture.path("comparisonCases").valueStream())
                        .toList();
        assertThat(cases.stream().map(item -> item.path("kind").asText()))
                .contains("direct", "synonym", "paraphrase", "insufficient");
        var metrics = new SimpleMeterRegistry();
        var reports = new java.util.LinkedHashMap<String, Object>();
        boolean expanded = KnowledgeCorpusFixture.RESOURCE.equals(resource);
        if (expanded)
            reports.put(
                    "fixture",
                    Map.of(
                            "resource",
                            resource,
                            "sha256",
                            fixture.path("fixtureSha256").asText(),
                            "documents",
                            fixture.path("documents").size(),
                            "cases",
                            cases.size(),
                            "annotationStatus",
                            fixture.path("manifest").path("annotationStatus").asText()));
        try {
            reports.put(
                    "lexical", report("lexical", collect(snapshots, corpus, cases, null), json));
            if (vectors != null) {
                var settings =
                        new KnowledgeRetrievalSettings(
                                Integer.parseInt(System.getProperty("agent.retrieval.rrf-k", "60")),
                                Integer.parseInt(
                                        System.getProperty("agent.retrieval.lexical-top-n", "30")),
                                Integer.parseInt(
                                        System.getProperty("agent.retrieval.vector-top-n", "30")),
                                Integer.parseInt(
                                        System.getProperty(
                                                "agent.retrieval.max-per-heading", "2")));
                var hybrid = new HybridKnowledgeSearch(vectors, metrics, settings);
                var results = collect(snapshots, corpus, cases, hybrid);
                assertThat(metrics.find("knowledge.retrieval.failures").counter())
                        .as("Hybrid evaluation must complete without vector failures")
                        .isNull();
                reports.put(
                        "hybrid", report("hybrid (" + hybrid.fingerprint() + ")", results, json));
            }
        } finally {
            metrics.close();
        }
        String defaultReport =
                expanded
                        ? "target/knowledge-retrieval-expanded.json"
                        : "target/knowledge-retrieval-hybrid.json";
        Files.writeString(
                Path.of(System.getProperty("knowledge.eval.hybridReport", defaultReport)),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(reports));
    }

    private KnowledgeVectors localVectors(String model) {
        int port = Integer.parseInt(System.getProperty("knowledge.eval.embeddingPort", "18092"));
        int dimension =
                Integer.parseInt(System.getProperty("knowledge.eval.embeddingDimension", "384"));
        assertThat(port).isBetween(1, 65535);
        assertThat(dimension).isBetween(1, 4096);
        assertThat(model).isNotBlank();
        var embedding =
                OpenAiEmbeddingModel.builder()
                        .baseUrl("http://127.0.0.1:" + port + "/v1")
                        .modelName(model)
                        .apiKey("local")
                        .dimensions(dimension)
                        .maxRetries(0)
                        .timeout(Duration.ofSeconds(3))
                        .build();
        return new LangChain4jKnowledgeVectors(
                embedding, new InMemoryEmbeddingStore<TextSegment>(), model + ":" + dimension);
    }

    private List<Result> collect(
            List<KnowledgeSnapshot> snapshots,
            List<Hit> corpus,
            List<JsonNode> cases,
            HybridKnowledgeSearch hybrid) {
        var results = new ArrayList<Result>();
        for (var item : cases) {
            var scope = KnowledgeRetrievalEvaluationTest.strings(item.path("scope"));
            var expected = KnowledgeRetrievalEvaluationTest.strings(item.path("expected"));
            var selected = snapshots.stream().filter(s -> scope.contains(s.id())).toList();
            var candidates = corpus.stream().filter(h -> scope.contains(h.documentId())).toList();
            assertThat(candidates.stream().map(KnowledgeRetrievalEvaluationTest::source).toList())
                    .as("Fixture evidence for %s", item.path("id").asText())
                    .containsAll(expected);
            String query = item.path("question").asText();
            if (results.isEmpty()) search(selected, candidates, query, hybrid); // 首个请求预热，不计入耗时。
            long start = System.nanoTime();
            var hits = search(selected, candidates, query, hybrid);
            long micros = (System.nanoTime() - start) / 1000;
            assertThat(hits)
                    .hasSizeLessThanOrEqualTo(LIMIT)
                    .allMatch(
                            h ->
                                    scope.contains(h.documentId())
                                            && KnowledgeText.isEvidence(h.chunk()));
            results.add(
                    new Result(
                            item.path("id").asText(),
                            item.path("kind").asText(),
                            expected,
                            hits.stream().map(KnowledgeRetrievalEvaluationTest::source).toList(),
                            micros,
                            item.path("split").asText("unspecified")));
        }
        return results;
    }

    private List<Hit> search(
            List<KnowledgeSnapshot> snapshots,
            List<Hit> candidates,
            String query,
            HybridKnowledgeSearch hybrid) {
        return hybrid == null
                ? KnowledgeText.rank(candidates, query, LIMIT)
                : hybrid.search(snapshots, candidates, query, LIMIT);
    }

    private Map<String, Object> report(String mode, List<Result> results, JsonMapper json) {
        var groups =
                results.stream()
                        .collect(
                                Collectors.groupingBy(
                                        Result::kind, java.util.TreeMap::new, Collectors.toList()));
        groups.put("all", results);
        groups.forEach(
                (kind, rows) -> {
                    long labels = rows.stream().mapToLong(row -> row.expected.size()).sum();
                    long recalled =
                            rows.stream()
                                    .flatMap(
                                            row ->
                                                    row.expected.stream()
                                                            .filter(row.retrieved::contains))
                                    .count();
                    var elapsed = rows.stream().mapToLong(Result::micros).sorted().toArray();
                    long p95 = elapsed[(int) Math.ceil(elapsed.length * 0.95) - 1];
                    var report =
                            new java.util.LinkedHashMap<>(
                                    KnowledgeRetrievalMetrics.summarize(
                                            rows.stream()
                                                    .map(
                                                            row ->
                                                                    new KnowledgeRetrievalMetrics
                                                                            .Sample(
                                                                            row.kind(),
                                                                            row.expected(),
                                                                            row.retrieved()))
                                                    .toList(),
                                            List.of(1, 3, LIMIT)));
                    report.put("mode", mode);
                    report.put("kind", kind);
                    report.put("recalledEvidenceAt6", recalled);
                    report.put("expectedEvidence", labels);
                    report.put("p95Micros", p95);
                    System.out.println(json.writeValueAsString(report));
                });
        System.out.println(json.writeValueAsString(Map.of("mode", mode, "details", results)));
        var splitMetrics = new java.util.TreeMap<String, Object>();
        results.stream()
                .collect(Collectors.groupingBy(Result::split))
                .forEach(
                        (split, rows) ->
                                splitMetrics.put(
                                        split,
                                        KnowledgeRetrievalMetrics.byKind(
                                                rows.stream()
                                                        .map(
                                                                row ->
                                                                        new KnowledgeRetrievalMetrics
                                                                                .Sample(
                                                                                row.kind(),
                                                                                row.expected(),
                                                                                row.retrieved()))
                                                        .toList(),
                                                List.of(1, 3, LIMIT))));
        return Map.of(
                "mode",
                mode,
                "details",
                results,
                "metricsBySplit",
                splitMetrics,
                "metricsByKind",
                KnowledgeRetrievalMetrics.byKind(
                        results.stream()
                                .map(
                                        row ->
                                                new KnowledgeRetrievalMetrics.Sample(
                                                        row.kind(),
                                                        row.expected(),
                                                        row.retrieved()))
                                .toList(),
                        List.of(1, 3, LIMIT)),
                "timingScope",
                "in-memory authorized corpus + ranking + optional model; excludes SQL");
    }
}
