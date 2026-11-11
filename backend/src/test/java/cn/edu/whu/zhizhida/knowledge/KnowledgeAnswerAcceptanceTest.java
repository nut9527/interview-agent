package cn.edu.whu.zhizhida.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.edu.whu.zhizhida.ai.AiGuardFixtures;
import cn.edu.whu.zhizhida.ai.DeepSeekChatClient;
import cn.edu.whu.zhizhida.ai.LangChain4jKnowledgeVectors;
import cn.edu.whu.zhizhida.ai.api.*;
import cn.edu.whu.zhizhida.common.Sha256;
import cn.edu.whu.zhizhida.interview.api.KnowledgeSnapshot;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeAnswer;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Hit;
import cn.edu.whu.zhizhida.knowledge.domain.KnowledgeText;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/** 真实 BGE 召回与生产回答器的单次生成验收；仅发送本文件及冻结夹具中的合成资料。 */
@EnabledIfEnvironmentVariable(named = "RUN_KNOWLEDGE_ANSWER_ACCEPTANCE", matches = "true")
class KnowledgeAnswerAcceptanceTest {
    record Case(String id, String question, List<String> scope, List<String> expected, boolean conflict) {}

    @Test
    void distinguishRetrievedCandidatesFromSupportedAnswers() throws Exception {
        var json = new JsonMapper();
        var fixture = json.readTree(getClass().getResourceAsStream("/knowledge-retrieval.json"));
        var snapshots = new ArrayList<KnowledgeSnapshot>();
        for (var d : fixture.path("documents")) snapshots.add(snapshot(d.path("id").asText(), d.path("title").asText(), d.path("content").asText()));
        snapshots.add(snapshot("policy-a", "订单缓存规则", "## 规则\n\n订单提交后必须删除缓存。"));
        snapshots.add(snapshot("policy-b", "订单缓存备忘", "## 规则\n\n订单提交后必须保留缓存，不执行删除。"));
        var cases = new ArrayList<Case>();
        var selected = Set.of("I01", "I02", "I03", "I04", "P05", "P01", "P02", "P03");
        for (var group : List.of(fixture.path("cases"), fixture.path("comparisonCases")))
            for (var q : group) if (selected.contains(q.path("id").asText())) cases.add(new Case(q.path("id").asText(),
                    q.path("question").asText(), KnowledgeRetrievalEvaluationTest.strings(q.path("scope")),
                    KnowledgeRetrievalEvaluationTest.strings(q.path("expected")), false));
        cases.add(new Case("comparison", "比较商品正常缓存与空值缓存的保存时长，以及创建商品后的处理。", List.of("cache"),
                List.of("cache:TTL", "cache:空值缓存"), false));
        cases.add(new Case("conflict", "两份资料对订单提交后的缓存处理是否一致？", List.of("policy-a", "policy-b"),
                List.of("policy-a:规则", "policy-b:规则"), true));
        String selection = System.getProperty("knowledge.eval.answerCases");
        if (selection != null) {
            var ids = Set.of(selection.split(","));
            cases.removeIf(c -> !ids.contains(c.id()));
            assertThat(cases).hasSize(ids.size());
        }

        int port = Integer.getInteger("knowledge.eval.embeddingPort", 18192);
        var embedding = OpenAiEmbeddingModel.builder().baseUrl("http://127.0.0.1:" + port + "/v1").apiKey("local")
                .modelName("bge-small-zh-v1.5-q").dimensions(512).maxRetries(0).timeout(Duration.ofSeconds(10)).build();
        var vectors = new LangChain4jKnowledgeVectors(embedding, new InMemoryEmbeddingStore<TextSegment>(), "bge-small-zh-v1.5-q:512");
        var corpus = new ArrayList<Hit>();
        for (var d : snapshots) {
            var chunks = KnowledgeText.split(d.id(), d.content()).stream().filter(KnowledgeText::isEvidence).toList();
            chunks.forEach(c -> corpus.add(new Hit(d.id(), d.title(), d.sourceName(), 1, c, 0)));
            vectors.index(new KnowledgeVectors.Scope(d.id(), 1, d.sha256()), chunks.stream()
                    .map(c -> new KnowledgeVectors.Fragment(c.id(), KnowledgeText.embeddingText(c))).toList());
        }
        String model = System.getenv("ACCEPTANCE_MODEL");
        var provider = new DeepSeekChatClient(System.getenv("AI_API_KEY"), System.getenv("ACCEPTANCE_BASE_URL"), model,
                "disabled", 25, json, AiGuardFixtures.guard(), BusinessMetrics.disabled());
        Path destination = Path.of(System.getProperty("knowledge.eval.answerReport", "target/knowledge-answer-acceptance.json"));
        Files.createDirectories(destination.getParent());
        var report = new LinkedHashMap<String, Object>();
        var attempts = new ArrayList<Map<String, Object>>();
        report.put("scope", "Synthetic corpus; real local BGE + production HybridKnowledgeSearch and DefaultKnowledgeAnswerer; excludes SQL/worker/API");
        report.put("model", model); report.put("embeddingModel", "bge-small-zh-v1.5-q:512");
        report.put("maxRequests", 10); report.put("maxOutputTokensPerRequest", 1500);
        report.put("promptSha256", Sha256.hex(DefaultKnowledgeAnswerer.PROMPT));
        report.put("recordedAt", java.time.Instant.now().toString()); report.put("attempts", attempts);
        int[] requests = {0};
        var metrics = new SimpleMeterRegistry();
        try {
            var search = new HybridKnowledgeSearch(vectors, metrics);
            for (var test : cases) {
                var row = new LinkedHashMap<String, Object>(); attempts.add(row);
                row.put("id", test.id()); row.put("question", test.question()); row.put("expectedEvidence", test.expected());
                row.put("status", "STARTED"); row.put("passed", false);
                var docs = snapshots.stream().filter(s -> test.scope().contains(s.id())).toList();
                var hits = search.search(docs, corpus.stream().filter(h -> test.scope().contains(h.documentId())).toList(), test.question(), 6);
                row.put("retrieved", hits);
                JsonChatClient tracked = (system, user, temperature, maxTokens) -> {
                    if (++requests[0] > 10) throw new IllegalStateException("Acceptance request budget exceeded");
                    row.put("status", "SENT"); row.put("reservedOutputTokens", 1500);
                    write(json, destination, report);
                    var response = provider.complete(system, user, temperature, Math.min(maxTokens, 1500));
                    row.put("usage", response.usage()); row.put("rawOutput", response.payload());
                    return response;
                };
                long started = System.nanoTime();
                try {
                    var answer = hits.isEmpty() ? DefaultKnowledgeAnswerer.insufficient()
                            : new DefaultKnowledgeAnswerer(tracked, json, AiMode.MODEL).answer(test.question(), hits);
                    row.put("answer", answer); row.put("status", "RETURNED");
                    var cited = answer.claims().stream().flatMap(c -> c.citations().stream())
                            .map(c -> hits.stream().filter(h -> h.chunk().id().equals(c.chunkId())).findFirst().orElseThrow())
                            .map(KnowledgeRetrievalEvaluationTest::source).distinct().toList();
                    boolean supported = answer.claims().stream().flatMap(c -> c.citations().stream()).allMatch(c -> hits.stream()
                            .anyMatch(h -> h.chunk().id().equals(c.chunkId()) && h.chunk().content().contains(c.quote())));
                    boolean passed = test.expected().isEmpty() ? !answer.sufficient() && answer.claims().isEmpty()
                            : answer.sufficient() && supported && cited.containsAll(test.expected())
                                    && (!test.conflict() || answer.claims().stream().anyMatch(c ->
                                            (c.text().contains("冲突") || c.text().contains("不一致") || c.text().contains("矛盾"))
                                                    && c.citations().stream().map(KnowledgeAnswer.Citation::documentId).distinct().count() >= 2));
                    row.put("citedEvidence", cited); row.put("quotesSupported", supported); row.put("passed", passed);
                } catch (RuntimeException failure) {
                    row.put("status", "FAILED"); row.put("failureType", failure.getClass().getSimpleName());
                }
                row.put("elapsedMillis", (System.nanoTime() - started) / 1_000_000);
                report.put("requests", requests[0]);
                write(json, destination, report);
            }
        } finally { metrics.close(); }
        assertThat(attempts).hasSize(cases.size()).allMatch(row -> Boolean.TRUE.equals(row.get("passed")));
    }

    private static KnowledgeSnapshot snapshot(String id, String title, String content) {
        return new KnowledgeSnapshot(id, title, "synthetic.md", 1, Sha256.hex(content), content, List.of());
    }

    private static void write(JsonMapper json, Path path, Map<String, Object> report) {
        try { Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(report)); }
        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
}
