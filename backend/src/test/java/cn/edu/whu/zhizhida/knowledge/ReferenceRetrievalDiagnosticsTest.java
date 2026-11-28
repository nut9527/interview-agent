package cn.edu.whu.zhizhida.knowledge;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;
import static org.assertj.core.api.Assertions.assertThat;

import cn.edu.whu.zhizhida.ai.LangChain4jKnowledgeVectors;
import cn.edu.whu.zhizhida.ai.api.KnowledgeVectors;
import cn.edu.whu.zhizhida.interview.api.KnowledgeSnapshot;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Hit;
import cn.edu.whu.zhizhida.knowledge.domain.KnowledgeText;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;

/** 比较选定资料和全库两种范围的检索候选，全库检索在内存中运行。 */
class ReferenceRetrievalDiagnosticsTest {
    private static final int CANDIDATES = 30;
    private static final boolean MEASURE = Boolean.getBoolean("knowledge.diagnostics.measure");
    private static final int LIMIT = 6;
    private static final String MODEL = "bge-small-zh-v1.5-q:512";
    private static final List<String> SCOPES = List.of("selected", "all");
    private static final List<String> CRITERIA = List.of("chapter", "exactQuote");

    private record Gold(String source, String quote, List<String> chunkIds) {}

    private record RankedChunk(int rank, String chunkId, Double score) {}

    private record GoldRanks(
            String source, Map<String, Integer> chapter, Map<String, Integer> exactQuote) {}

    private record PoolDiagnostic(
            Double recall,
            Double oracleRecallAt6,
            List<String> inPoolOutsideTop6,
            List<String> absentFromPool) {}

    private record ScopeResult(
            List<String> scope,
            int evidenceChunks,
            Map<String, List<RankedChunk>> stages,
            List<String> candidateUnion,
            List<GoldRanks> goldRanks,
            Map<String, Map<String, PoolDiagnostic>> diagnostics,
            Map<String, List<Long>> timingsMicros) {}

    private record CaseResult(
            String id,
            String kind,
            String split,
            String question,
            List<Gold> gold,
            Map<String, ScopeResult> scopes) {}

    private record SemanticRanking(
            List<KnowledgeVectors.Match> matches, Map<String, Double> scores) {}

    @Test
    @EnabledIfSystemProperty(named = "knowledge.diagnostics.enabled", matches = "true")
    void diagnoseCandidatesAcrossSelectedScopeAndFullLibrary() throws Exception {
        var json = new JsonMapper();
        var fixture = KnowledgeCorpusFixture.load(json, KnowledgeCorpusFixture.RESOURCE);
        var answerability =
                json.readTree(
                        getClass()
                                .getResourceAsStream(
                                        "/rag-corpus/full-library-answerability.json"));
        var snapshots = new ArrayList<KnowledgeSnapshot>();
        var topics = new HashMap<String, String>();
        var corpus = new ArrayList<Hit>();
        var chunks = new LinkedHashMap<String, Hit>();
        var port =
                Integer.parseInt(
                        System.getProperty("knowledge.diagnostics.embeddingPort", "18093"));
        EmbeddingModel embedding =
                OpenAiEmbeddingModel.builder()
                        .baseUrl("http://127.0.0.1:" + port + "/v1")
                        .apiKey("local")
                        .modelName("bge-small-zh-v1.5-q")
                        .dimensions(512)
                        .maxRetries(0)
                        .timeout(Duration.ofSeconds(10))
                        .build();
        if (!MEASURE) embedding = new CachedQueries(embedding);
        var productionStore = new InMemoryEmbeddingStore<TextSegment>();
        var productionVectors = new LangChain4jKnowledgeVectors(embedding, productionStore, MODEL);
        var generations = new LinkedHashMap<KnowledgeVectors.Scope, String>();
        for (var document : fixture.path("documents")) {
            String id = document.path("id").asString(),
                    content = document.path("content").asString();
            String hash = sha256(content.getBytes(StandardCharsets.UTF_8));
            assertThat(answerability.path("corpusDocumentHashes").path(id).asString())
                    .isEqualTo(hash);
            var snapshot =
                    new KnowledgeSnapshot(
                            id,
                            document.path("title").asString(),
                            document.path("file").asString(),
                            1,
                            hash,
                            content,
                            List.of());
            snapshots.add(snapshot);
            topics.put(id, document.path("topic").asString());
            var scope = scope(snapshot);
            generations.put(
                    scope,
                    UUID.nameUUIDFromBytes(
                                    (id + ":1:" + hash + ":" + productionVectors.fingerprint())
                                            .getBytes(StandardCharsets.UTF_8))
                            .toString());
            var documentChunks =
                    KnowledgeText.split(id, 1, content).stream()
                            .filter(KnowledgeText::isEvidence)
                            .toList();
            for (var chunk : documentChunks) {
                var hit = new Hit(id, snapshot.title(), snapshot.sourceName(), 1, chunk, 0);
                corpus.add(hit);
                chunks.put(chunk.id(), hit);
            }
            productionVectors.index(
                    scope,
                    documentChunks.stream()
                            .map(
                                    chunk ->
                                            new KnowledgeVectors.Fragment(
                                                    chunk.id(), KnowledgeText.embeddingText(chunk)))
                            .toList());
        }
        assertThat(snapshots).hasSize(25);
        assertThat(fixture.path("cases").size()).isEqualTo(91);
        assertThat(answerability.path("corpusDocumentHashes").size()).isEqualTo(snapshots.size());
        var noAnswerIds =
                fixture.path("cases")
                        .valueStream()
                        .filter(q -> q.path("shouldAbstain").asBoolean())
                        .map(q -> q.path("id").asString())
                        .toList();
        assertThat(answerability.path("annotationStatus").asString())
                .isEqualTo("source-grounded-agent-review");
        assertThat(
                        answerability
                                .path("cases")
                                .valueStream()
                                .map(q -> q.path("id").asString())
                                .toList())
                .containsExactlyInAnyOrderElementsOf(noAnswerIds);
        answerability
                .path("cases")
                .forEach(
                        q -> {
                            assertThat(q.path("answerable").asBoolean()).isFalse();
                            assertThat(q.path("reason").asString()).isNotBlank();
                        });

        // 复制已建好的索引，使两种检索范围使用相同的向量、标识和插入顺序。
        var experimental =
                new FullLibraryVectors(
                        embedding,
                        InMemoryEmbeddingStore.fromJson(productionStore.serializeToJson()),
                        generations);
        var details = new ArrayList<CaseResult>();
        var metrics = new SimpleMeterRegistry();
        try {
            for (var item : fixture.path("cases")) {
                String query = item.path("question").asString();
                var selectedIds = KnowledgeRetrievalEvaluationTest.strings(item.path("scope"));
                var selected =
                        snapshots.stream().filter(d -> selectedIds.contains(d.id())).toList();
                assertThat(selected).hasSizeBetween(1, 5);
                var selectedScopes =
                        selected.stream().map(ReferenceRetrievalDiagnosticsTest::scope).toList();
                var semantic = experimental.rank(selectedScopes, query);
                assertThat(semantic.matches())
                        .as("Production vector parity: %s", item.path("id").asString())
                        .containsExactlyElementsOf(
                                productionVectors.search(selectedScopes, query, CANDIDATES));
                var gold = new ArrayList<Gold>();
                for (var evidence : item.path("evidence")) {
                    String source =
                            evidence.path("documentId").asString()
                                    + ":"
                                    + evidence.path("heading").asString();
                    String quote = evidence.path("quote").asString();
                    var matching =
                            corpus.stream()
                                    .filter(
                                            hit ->
                                                    source(hit).equals(source)
                                                            && hit.chunk()
                                                                    .content()
                                                                    .contains(quote))
                                    .map(hit -> hit.chunk().id())
                                    .toList();
                    assertThat(matching)
                            .as("Exact quote chunks: %s/%s", item.path("id").asString(), source)
                            .isNotEmpty();
                    gold.add(new Gold(source, quote, matching));
                }
                var variants = new LinkedHashMap<String, ScopeResult>();
                variants.put(
                        "selected",
                        diagnose(
                                selected,
                                corpus,
                                chunks,
                                query,
                                gold,
                                semantic,
                                metrics,
                                experimental));
                variants.put(
                        "all",
                        diagnose(
                                snapshots,
                                corpus,
                                chunks,
                                query,
                                gold,
                                experimental.rank(
                                        snapshots.stream()
                                                .map(ReferenceRetrievalDiagnosticsTest::scope)
                                                .toList(),
                                        query),
                                metrics,
                                experimental));
                details.add(
                        new CaseResult(
                                item.path("id").asString(),
                                item.path("kind").asString(),
                                item.path("split").asString(),
                                query,
                                gold,
                                variants));
            }
            assertThat(metrics.find("knowledge.retrieval.failures").counter()).isNull();
        } finally {
            metrics.close();
        }
        var summary = new LinkedHashMap<String, Object>();
        for (String scope : SCOPES) {
            var criteria = new LinkedHashMap<String, Object>();
            for (String criterion : CRITERIA) {
                var groups = new LinkedHashMap<String, Object>();
                groups.put("all", summarize(details, scope, criterion, chunks));
                groups.put(
                        "bySplit", grouped(details, CaseResult::split, scope, criterion, chunks));
                groups.put("byKind", grouped(details, CaseResult::kind, scope, criterion, chunks));
                criteria.put(criterion, groups);
            }
            summary.put(scope, criteria);
        }
        var dictionary = new LinkedHashMap<String, Object>();
        chunks.forEach(
                (id, hit) ->
                        dictionary.put(
                                id,
                                Map.of(
                                        "documentId",
                                        hit.documentId(),
                                        "topic",
                                        topics.get(hit.documentId()),
                                        "title",
                                        hit.title(),
                                        "heading",
                                        hit.chunk().heading(),
                                        "startOffset",
                                        hit.chunk().startOffset(),
                                        "endOffset",
                                        hit.chunk().endOffset(),
                                        "content",
                                        hit.chunk().content())));
        var report = new LinkedHashMap<String, Object>();
        report.put("fixtureSha256", fixture.path("fixtureSha256").asString());
        report.put("documents", snapshots.size());
        report.put("evidenceChunks", chunks.size());
        report.put("cases", details.size());
        report.put(
                "controls",
                Map.of(
                        "rrfK",
                        60,
                        "lexicalTopN",
                        CANDIDATES,
                        "vectorTopN",
                        CANDIDATES,
                        "maxPerHeading",
                        2,
                        "limit",
                        LIMIT,
                        "minimumVectorScore",
                        0.55,
                        "model",
                        MODEL,
                        "selectedScopeProductionParityCases",
                        details.size()));
        report.put(
                "interpretation",
                Map.of(
                        "fullLibrary",
                        "Experimental in-memory search over the complete corpus; the production adapter retains its five-document limit.",
                        "ranks",
                        "One-based; zero means absent. RRF Top30 is before section diversification; finalTop6 uses the production quota of two.",
                        "candidateUnion",
                        "Unordered deduplicated union of lexical Top30 and vector Top30, at most 60 chunks.",
                        "scores",
                        "Lexical and vector arrays retain their own scores; RRF/final scores are null because only production ordering is observed.",
                        "exactQuote",
                        "A hit must contain the entire original gold quote in one chunk of its labeled document and heading; alternative evidence is not reannotated.",
                        "noAnswer",
                        "Candidate nonemptiness on the reviewed 13 no-answer cases is not an answer-generation error rate.",
                        "timing",
                        MEASURE
                                ? "Three warm measurements per route: lexical Top6 and real HTTP embedding + vector Top30 + RRF + quota; excludes indexing and scope filtering."
                                : "Diagnostic rankings only; query embeddings are cached for parity and replay, so this is not a latency benchmark."));
        report.put("fullLibraryAnswerability", answerability);
        report.put("chunks", dictionary);
        report.put("summary", summary);
        report.put("details", details);
        Files.writeString(
                Path.of(
                        System.getProperty(
                                "knowledge.diagnostics.report",
                                "target/reference-retrieval-diagnostics.json")),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
    }

    private static ScopeResult diagnose(
            List<KnowledgeSnapshot> selected,
            List<Hit> corpus,
            Map<String, Hit> chunks,
            String query,
            List<Gold> gold,
            SemanticRanking semantic,
            SimpleMeterRegistry metrics,
            FullLibraryVectors experimental) {
        var ids = selected.stream().map(KnowledgeSnapshot::id).toList();
        var candidates = corpus.stream().filter(hit -> ids.contains(hit.documentId())).toList();
        var lexical = KnowledgeText.rank(candidates, query, CANDIDATES);
        var pool = new LinkedHashSet<String>();
        lexical.forEach(hit -> pool.add(hit.chunk().id()));
        semantic.matches().forEach(match -> pool.add(match.chunkId()));
        var frozen =
                replay(
                        semantic.matches(),
                        selected.stream().map(ReferenceRetrievalDiagnosticsTest::scope).toList(),
                        query);
        var search = new HybridKnowledgeSearch(frozen, metrics, new KnowledgeRetrievalSettings());
        var fused = search.rank(selected, candidates, query);
        assertThat(fused.stream().map(hit -> hit.chunk().id()))
                .containsExactlyInAnyOrderElementsOf(pool);
        var stages = new LinkedHashMap<String, List<RankedChunk>>();
        stages.put("lexicalTop30", rows(lexical, true));
        var vectorRows = new ArrayList<RankedChunk>();
        for (var match : semantic.matches())
            vectorRows.add(
                    new RankedChunk(
                            vectorRows.size() + 1,
                            match.chunkId(),
                            semantic.scores().get(match.chunkId())));
        stages.put("vectorTop30", vectorRows);
        stages.put("rrfTop30", rows(fused.stream().limit(CANDIDATES).toList(), false));
        stages.put("finalTop6", rows(search.search(selected, candidates, query, LIMIT), false));
        var goldRanks =
                gold.stream()
                        .map(
                                g -> {
                                    var chapter = new LinkedHashMap<String, Integer>();
                                    var quote = new LinkedHashMap<String, Integer>();
                                    stages.forEach(
                                            (stage, ranking) -> {
                                                chapter.put(
                                                        stage,
                                                        rank(
                                                                ranking,
                                                                chunk ->
                                                                        source(chunks.get(chunk))
                                                                                .equals(
                                                                                        g
                                                                                                .source())));
                                                quote.put(
                                                        stage,
                                                        rank(ranking, g.chunkIds()::contains));
                                            });
                                    return new GoldRanks(g.source(), chapter, quote);
                                })
                        .toList();
        var diagnostics = new LinkedHashMap<String, Map<String, PoolDiagnostic>>();
        for (String criterion : CRITERIA) {
            diagnostics.put(
                    criterion,
                    Map.of(
                            "unionUpTo60",
                            poolDiagnostic(
                                    gold,
                                    List.copyOf(pool),
                                    stages.get("finalTop6"),
                                    criterion,
                                    chunks),
                            "rrfTop30",
                            poolDiagnostic(
                                    gold,
                                    stages.get("rrfTop30").stream()
                                            .map(RankedChunk::chunkId)
                                            .toList(),
                                    stages.get("finalTop6"),
                                    criterion,
                                    chunks)));
        }
        return new ScopeResult(
                ids,
                candidates.size(),
                stages,
                List.copyOf(pool),
                goldRanks,
                diagnostics,
                MEASURE
                        ? measure(selected, candidates, query, stages, experimental, metrics)
                        : Map.of());
    }

    private static Map<String, List<Long>> measure(
            List<KnowledgeSnapshot> selected,
            List<Hit> candidates,
            String query,
            Map<String, List<RankedChunk>> stages,
            FullLibraryVectors vectors,
            SimpleMeterRegistry metrics) {
        var scopes = selected.stream().map(ReferenceRetrievalDiagnosticsTest::scope).toList();
        var lexicalTimes = new ArrayList<Long>();
        var hybridTimes = new ArrayList<Long>();
        for (int round = -1; round < 3; round++) {
            long start = System.nanoTime();
            var lexical = KnowledgeText.rank(candidates, query, LIMIT);
            long lexicalMicros = (System.nanoTime() - start) / 1000;
            start = System.nanoTime();
            var semantic = vectors.rank(scopes, query);
            var search =
                    new HybridKnowledgeSearch(
                            replay(semantic.matches(), scopes, query),
                            metrics,
                            new KnowledgeRetrievalSettings());
            var hybrid = search.search(selected, candidates, query, LIMIT);
            long hybridMicros = (System.nanoTime() - start) / 1000;
            assertThat(lexical.stream().map(h -> h.chunk().id()).toList())
                    .isEqualTo(
                            stages.get("lexicalTop30").stream()
                                    .limit(LIMIT)
                                    .map(RankedChunk::chunkId)
                                    .toList());
            assertThat(hybrid.stream().map(h -> h.chunk().id()).toList())
                    .isEqualTo(stages.get("finalTop6").stream().map(RankedChunk::chunkId).toList());
            if (round >= 0) {
                lexicalTimes.add(lexicalMicros);
                hybridTimes.add(hybridMicros);
            }
        }
        return Map.of("lexical", lexicalTimes, "hybrid", hybridTimes);
    }

    private static PoolDiagnostic poolDiagnostic(
            List<Gold> gold,
            List<String> pool,
            List<RankedChunk> finalHits,
            String criterion,
            Map<String, Hit> chunks) {
        var present =
                gold.stream()
                        .filter(
                                g ->
                                        pool.stream()
                                                .anyMatch(id -> matches(g, id, criterion, chunks)))
                        .toList();
        var missing = gold.stream().filter(g -> !present.contains(g)).map(Gold::source).toList();
        var displaced =
                present.stream()
                        .filter(
                                g ->
                                        finalHits.stream()
                                                .noneMatch(
                                                        row ->
                                                                matches(
                                                                        g,
                                                                        row.chunkId(),
                                                                        criterion,
                                                                        chunks)))
                        .map(Gold::source)
                        .toList();
        return new PoolDiagnostic(
                gold.isEmpty() ? null : (double) present.size() / gold.size(),
                gold.isEmpty() ? null : (double) Math.min(present.size(), LIMIT) / gold.size(),
                displaced,
                missing);
    }

    private static boolean matches(
            Gold gold, String chunk, String criterion, Map<String, Hit> chunks) {
        return criterion.equals("exactQuote")
                ? gold.chunkIds().contains(chunk)
                : source(chunks.get(chunk)).equals(gold.source());
    }

    private static Map<String, Object> summarize(
            List<CaseResult> cases, String scope, String criterion, Map<String, Hit> chunks) {
        var stages = new LinkedHashMap<String, Object>();
        for (String stage : List.of("lexicalTop30", "vectorTop30", "rrfTop30", "finalTop6")) {
            var samples =
                    cases.stream()
                            .map(
                                    item ->
                                            new KnowledgeRetrievalMetrics.Sample(
                                                    item.kind(),
                                                    item.gold().stream().map(Gold::source).toList(),
                                                    item
                                                            .scopes()
                                                            .get(scope)
                                                            .stages()
                                                            .get(stage)
                                                            .stream()
                                                            .map(
                                                                    row ->
                                                                            item.gold().stream()
                                                                                    .filter(
                                                                                            g ->
                                                                                                    matches(
                                                                                                            g,
                                                                                                            row
                                                                                                                    .chunkId(),
                                                                                                            criterion,
                                                                                                            chunks))
                                                                                    .map(
                                                                                            Gold
                                                                                                    ::source)
                                                                                    .findFirst()
                                                                                    .orElse(
                                                                                            "unlabeled:"
                                                                                                    + row
                                                                                                            .chunkId()))
                                                            .toList()))
                            .toList();
            var metrics =
                    new LinkedHashMap<>(
                            KnowledgeRetrievalMetrics.summarize(
                                    samples,
                                    stage.equals("finalTop6")
                                            ? List.of(1, 3, LIMIT)
                                            : List.of(1, 3, LIMIT, CANDIDATES)));
            long noAnswer = samples.stream().filter(s -> s.expected().isEmpty()).count();
            long nonempty =
                    samples.stream()
                            .filter(s -> s.expected().isEmpty() && !s.retrieved().isEmpty())
                            .count();
            metrics.put("noAnswerWithNonEmptyRetrieval", nonempty);
            metrics.put(
                    "nonEmptyRetrievalRateOnNoAnswer",
                    noAnswer == 0 ? null : (double) nonempty / noAnswer);
            if (stage.equals("finalTop6")) {
                metrics.put(
                        "casesWithFirstGoldRank4To6",
                        cases.stream()
                                .filter(
                                        item -> {
                                            int first =
                                                    rank(
                                                            item.scopes()
                                                                    .get(scope)
                                                                    .stages()
                                                                    .get(stage),
                                                            id ->
                                                                    item.gold().stream()
                                                                            .anyMatch(
                                                                                    g ->
                                                                                            matches(
                                                                                                    g,
                                                                                                    id,
                                                                                                    criterion,
                                                                                                    chunks)));
                                            return first >= 4 && first <= LIMIT;
                                        })
                                .map(CaseResult::id)
                                .toList());
            }
            stages.put(stage, metrics);
        }
        var positives = cases.stream().filter(item -> !item.gold().isEmpty()).toList();
        var pools = new LinkedHashMap<String, Object>();
        for (String pool : List.of("unionUpTo60", "rrfTop30")) {
            var rows =
                    positives.stream()
                            .map(
                                    item ->
                                            item.scopes()
                                                    .get(scope)
                                                    .diagnostics()
                                                    .get(criterion)
                                                    .get(pool))
                            .toList();
            var values = new LinkedHashMap<String, Object>();
            values.put("positiveCases", rows.size());
            values.put(
                    "recall",
                    rows.isEmpty()
                            ? null
                            : rows.stream()
                                    .mapToDouble(PoolDiagnostic::recall)
                                    .average()
                                    .orElseThrow());
            values.put(
                    "oracleRecallAt6",
                    rows.isEmpty()
                            ? null
                            : rows.stream()
                                    .mapToDouble(PoolDiagnostic::oracleRecallAt6)
                                    .average()
                                    .orElseThrow());
            values.put(
                    "goldInPoolOutsideTop6",
                    rows.stream().mapToInt(row -> row.inPoolOutsideTop6().size()).sum());
            values.put(
                    "goldAbsentFromPool",
                    rows.stream().mapToInt(row -> row.absentFromPool().size()).sum());
            values.put(
                    "casesWithGoldInPoolOutsideTop6",
                    positives.stream()
                            .filter(
                                    item ->
                                            !item.scopes()
                                                    .get(scope)
                                                    .diagnostics()
                                                    .get(criterion)
                                                    .get(pool)
                                                    .inPoolOutsideTop6()
                                                    .isEmpty())
                            .map(CaseResult::id)
                            .toList());
            values.put(
                    "casesWithGoldAbsentFromPool",
                    positives.stream()
                            .filter(
                                    item ->
                                            !item.scopes()
                                                    .get(scope)
                                                    .diagnostics()
                                                    .get(criterion)
                                                    .get(pool)
                                                    .absentFromPool()
                                                    .isEmpty())
                            .map(CaseResult::id)
                            .toList());
            pools.put(pool, values);
        }
        return Map.of("stages", stages, "candidatePools", pools);
    }

    private static Map<String, Object> grouped(
            List<CaseResult> cases,
            java.util.function.Function<CaseResult, String> key,
            String scope,
            String criterion,
            Map<String, Hit> chunks) {
        var grouped =
                cases.stream()
                        .collect(
                                Collectors.groupingBy(
                                        key, java.util.TreeMap::new, Collectors.toList()));
        var summaries = new LinkedHashMap<String, Object>();
        grouped.forEach(
                (name, rows) -> summaries.put(name, summarize(rows, scope, criterion, chunks)));
        return summaries;
    }

    private static List<RankedChunk> rows(List<Hit> hits, boolean scores) {
        var rows = new ArrayList<RankedChunk>();
        for (var hit : hits)
            rows.add(
                    new RankedChunk(
                            rows.size() + 1,
                            hit.chunk().id(),
                            scores ? (double) hit.score() : null));
        return rows;
    }

    private static int rank(List<RankedChunk> ranking, Predicate<String> matches) {
        return ranking.stream()
                .filter(row -> matches.test(row.chunkId()))
                .mapToInt(RankedChunk::rank)
                .findFirst()
                .orElse(0);
    }

    private static String source(Hit hit) {
        return KnowledgeRetrievalEvaluationTest.source(hit);
    }

    private static KnowledgeVectors.Scope scope(KnowledgeSnapshot snapshot) {
        return new KnowledgeVectors.Scope(snapshot.id(), snapshot.version(), snapshot.sha256());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static KnowledgeVectors replay(
            List<KnowledgeVectors.Match> matches,
            List<KnowledgeVectors.Scope> scopes,
            String query) {
        return new KnowledgeVectors() {
            public String fingerprint() {
                return "frozen-local-bge-ranking";
            }

            public void index(Scope scope, List<Fragment> fragments) {
                throw new UnsupportedOperationException();
            }

            public void remove(Scope scope, String fingerprint) {
                throw new UnsupportedOperationException();
            }

            public List<Match> search(List<Scope> actual, String text, int limit) {
                assertThat(actual).containsExactlyElementsOf(scopes);
                assertThat(text).isEqualTo(query);
                assertThat(limit).isEqualTo(CANDIDATES);
                return matches;
            }
        };
    }

    /** 全库实验使用相同的 BGE 索引，放宽线上检索的资料数量上限。 */
    private record FullLibraryVectors(
            EmbeddingModel model,
            InMemoryEmbeddingStore<TextSegment> store,
            Map<KnowledgeVectors.Scope, String> generations) {
        SemanticRanking rank(List<KnowledgeVectors.Scope> scopes, String query) {
            var allowed = new LinkedHashMap<String, KnowledgeVectors.Scope>();
            scopes.forEach(scope -> allowed.put(generations.get(scope), scope));
            var found =
                    store.search(
                            EmbeddingSearchRequest.builder()
                                    .queryEmbedding(model.embed(query).content())
                                    .maxResults(CANDIDATES)
                                    .minScore(0.55)
                                    .filter(
                                            Filter.and(
                                                    metadataKey("generation")
                                                            .isIn(allowed.keySet()),
                                                    metadataKey("kind").isEqualTo("chunk")))
                                    .build());
            var matches = new ArrayList<KnowledgeVectors.Match>();
            var scores = new LinkedHashMap<String, Double>();
            for (var match : found.matches()) {
                var metadata = match.embedded().metadata();
                String chunk = metadata.getString("chunkId");
                matches.add(
                        new KnowledgeVectors.Match(
                                allowed.get(metadata.getString("generation")), chunk));
                scores.put(chunk, match.score());
            }
            return new SemanticRanking(matches, scores);
        }
    }

    /** 每道题计算一次查询向量，供排名一致性校验和两种范围的检索实验复用。 */
    private static final class CachedQueries implements EmbeddingModel {
        private final EmbeddingModel delegate;
        private final Map<String, Response<Embedding>> queries = new HashMap<>();

        private CachedQueries(EmbeddingModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public Response<Embedding> embed(String text) {
            return queries.computeIfAbsent(text, delegate::embed);
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            return delegate.embedAll(segments);
        }
    }
}
