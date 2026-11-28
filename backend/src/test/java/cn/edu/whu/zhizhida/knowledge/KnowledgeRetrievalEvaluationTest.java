package cn.edu.whu.zhizhida.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Hit;
import cn.edu.whu.zhizhida.knowledge.domain.KnowledgeText;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 使用固定的来源标注和确定性语料，执行生产环境的检索服务与排序逻辑。 */
class KnowledgeRetrievalEvaluationTest {
    @Test
    void evaluateFixedQuestions() throws Exception {
        var json = new JsonMapper();
        var fixture = json.readTree(getClass().getResourceAsStream("/knowledge-retrieval.json"));
        var corpus = new ArrayList<Hit>();
        for (var d : fixture.path("documents"))
            for (var c : KnowledgeText.split(d.path("id").asString(), d.path("content").asString()))
                corpus.add(
                        new Hit(
                                d.path("id").asString(),
                                d.path("title").asString(),
                                "fixture.md",
                                1,
                                c,
                                0));
        var rows = new ArrayList<Map<String, Object>>();
        var samples = new ArrayList<KnowledgeRetrievalMetrics.Sample>();
        var gatedIds =
                fixture.path("cases").valueStream().map(q -> q.path("id").asString()).toList();
        boolean baseline = Boolean.getBoolean("knowledge.eval.baseline");
        // 单独预热；计时仅涵盖内存候选匹配与生产排序逻辑，
        // 不包含数据库查询或模型调用耗时。
        var warm = service(corpus, List.of("cache"));
        for (int n = 0; n < 50; n++) warm.search("缓存 TTL", List.of("cache"), 6);
        for (var q :
                Stream.concat(
                                fixture.path("cases").valueStream(),
                                fixture.path("comparisonCases").valueStream())
                        .toList()) {
            var scope = strings(q.path("scope"));
            var expected = strings(q.path("expected"));
            var service = service(corpus, scope);
            long start = System.nanoTime();
            var hits = service.search(q.path("question").asString(), scope, 6);
            long elapsed = System.nanoTime() - start;
            var sources = hits.stream().map(KnowledgeRetrievalEvaluationTest::source).toList();
            assertThat(hits).allMatch(h -> KnowledgeText.isEvidence(h.chunk()));
            samples.add(
                    new KnowledgeRetrievalMetrics.Sample(
                            q.path("kind").asString(), expected, sources));
            boolean pass =
                    sources.containsAll(expected)
                            && (!q.has("first")
                                    || !sources.isEmpty()
                                            && sources.getFirst()
                                                    .equals(q.path("first").asString()))
                            && (!q.path("noHits").asBoolean() || hits.isEmpty())
                            && hits.stream()
                                    .noneMatch(
                                            h ->
                                                    strings(q.path("excluded"))
                                                            .contains(h.documentId()));
            var row = new LinkedHashMap<String, Object>();
            row.put("id", q.path("id").asString());
            row.put("kind", q.path("kind").asString());
            row.put("question", q.path("question").asString());
            row.put("expected", expected);
            row.put("sources", sources);
            row.put("chunkIds", hits.stream().map(h -> h.chunk().id()).toList());
            row.put("elapsedMicros", elapsed / 1000.0);
            row.put("irrelevant", sources.stream().filter(s -> !expected.contains(s)).count());
            row.put("pass", pass);
            // 原有确定性用例仍是门禁；改写问法全部纳入报告，不因当前词项基线较弱而删标注。
            row.put("gated", gatedIds.contains(q.path("id").asString()));
            rows.add(row);
        }
        String report =
                System.getProperty(
                        "knowledge.eval.report", "target/knowledge-retrieval-current.json");
        Files.writeString(
                Path.of(report),
                json.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(
                                Map.of(
                                        "mode",
                                        baseline ? "baseline" : "current",
                                        "timingScope",
                                        "in-memory candidates + production ranking; excludes SQL and model",
                                        "cases",
                                        rows,
                                        "passed",
                                        rows.stream()
                                                .filter(r -> Boolean.TRUE.equals(r.get("pass")))
                                                .count(),
                                        "total",
                                        rows.size(),
                                        "metricsByKind",
                                        KnowledgeRetrievalMetrics.byKind(
                                                samples, List.of(1, 3, 6)))));
        if (!baseline)
            assertThat(
                            rows.stream()
                                    .filter(
                                            r ->
                                                    Boolean.TRUE.equals(r.get("gated"))
                                                            && Boolean.FALSE.equals(r.get("pass")))
                                    .toList())
                    .as("Failed retrieval cases")
                    .isEmpty();
    }

    static String source(Hit h) {
        return h.documentId()
                + ":"
                + h.chunk().heading()
                + (!KnowledgeText.isEvidence(h.chunk()) ? " (heading)" : "");
    }

    static List<String> strings(JsonNode node) {
        var result = new ArrayList<String>();
        node.forEach(n -> result.add(n.asString()));
        return result;
    }

    static KnowledgeService service(List<Hit> corpus, List<String> scope) {
        var repository = org.mockito.Mockito.mock(KnowledgeRepository.class);
        var users =
                org.mockito.Mockito.mock(cn.edu.whu.zhizhida.auth.api.CurrentUserProvider.class);
        org.mockito.Mockito.when(users.requireUserId()).thenReturn(1L);
        for (String id : scope)
            org.mockito.Mockito.when(repository.find(1L, id))
                    .thenReturn(
                            Optional.of(
                                    new cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument(
                                            id, id, "fixture", 1, "", null, List.of())));
        org.mockito.Mockito.when(
                        repository.candidates(
                                org.mockito.ArgumentMatchers.eq(1L),
                                org.mockito.ArgumentMatchers.eq(scope),
                                org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(
                        call -> {
                            List<String> terms = call.getArgument(2);
                            return corpus.stream()
                                    .filter(h -> scope.contains(h.documentId()))
                                    .filter(
                                            h ->
                                                    terms.stream()
                                                            .anyMatch(
                                                                    t ->
                                                                            (h.chunk().heading()
                                                                                            + "\n"
                                                                                            + h.chunk()
                                                                                                    .content())
                                                                                    .toLowerCase(
                                                                                            Locale
                                                                                                    .ROOT)
                                                                                    .contains(t)))
                                    .toList();
                        });
        return new KnowledgeService(repository, users);
    }
}
