package cn.edu.whu.zhizhida.ai;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import cn.edu.whu.zhizhida.ai.api.KnowledgeVectors;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;

/**
 * 索引代际不可变：先写完所有片段再发布清单，未完成的代际不可见。
 * Embedding 版本包含模型、维度和预处理方式；索引准入由调用方控制。
 */
public final class LangChain4jKnowledgeVectors implements KnowledgeVectors {
    private final EmbeddingModel model;
    private final EmbeddingStore<TextSegment> store;
    private final String embeddingVersion;
    private final double minScore;

    // 预处理改变必须切换代际；RRF 参数只影响查询，不应触发重新嵌入。
    public String fingerprint() {
        return "knowledge-heading-body-blocks-v4:" + embeddingVersion;
    }

    public String retrievalFingerprint() {
        return fingerprint() + ":min-score=" + minScore;
    }

    public LangChain4jKnowledgeVectors(EmbeddingModel model, EmbeddingStore<TextSegment> store,
            String embeddingVersion) {
        this(model, store, embeddingVersion, 0.55);
    }

    public LangChain4jKnowledgeVectors(EmbeddingModel model, EmbeddingStore<TextSegment> store, String embeddingVersion,
            double minScore) {
        this.model = Objects.requireNonNull(model);
        this.store = Objects.requireNonNull(store);
        if (embeddingVersion == null || embeddingVersion.isBlank())
            throw new IllegalArgumentException("Embedding version required");
        this.embeddingVersion = embeddingVersion;
        if (!Double.isFinite(minScore) || minScore < 0 || minScore > 1)
            throw new IllegalArgumentException("Vector min score must be between 0 and 1");
        this.minScore = minScore;
    }

    private String generation(Scope scope) {
        return generation(scope, fingerprint());
    }

    private String generation(Scope scope, String fingerprint) {
        return UUID.nameUUIDFromBytes((scope.documentId() + ":" + scope.version() + ":" + scope.contentSha256()
                + ":" + fingerprint).getBytes(StandardCharsets.UTF_8)).toString();
    }

    public void remove(Scope scope, String indexFingerprint) {
        String generation = generation(scope, indexFingerprint);
        // 先取消发布，防止删除失败后暴露不完整代际。
        store.remove(id(generation, "manifest"));
        store.removeAll(metadataKey("generation").isEqualTo(generation));
    }

    private String id(String generation, String chunk) {
        return UUID.nameUUIDFromBytes((generation + ":" + chunk).getBytes(StandardCharsets.UTF_8)).toString();
    }

    public void index(Scope scope, List<Fragment> fragments) {
        if (fragments.isEmpty() || fragments.size() > 4096
                || fragments.stream().anyMatch(f -> f.text() == null || f.text().isBlank()
                        || f.text().length() > 4000 || f.chunkId() == null || f.chunkId().isBlank())
                || fragments.stream().map(Fragment::chunkId).distinct().count() != fragments.size())
            throw new IllegalArgumentException("Invalid existing chunk set");
        String generation = generation(scope);
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(120).toNanos();
        var segments = fragments.stream().map(f -> TextSegment.from(f.text(), Metadata.from(Map.of(
                "generation", generation, "kind", "chunk", "chunkId", f.chunkId())))).toList();
        // 按有界批次写入；请求失败时不发布新代际的清单。
        dev.langchain4j.data.embedding.Embedding marker = null;
        for (int offset = 0; offset < segments.size(); offset += 16) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
                throw new IllegalStateException("Index attempt interrupted or expired");
            var batch = segments.subList(offset, Math.min(offset + 16, segments.size()));
            var embeddings = model.embedAll(batch).content();
            if (embeddings.size() != batch.size())
                throw new IllegalStateException("Incomplete embedding batch");
            store.addAll(batch.stream().map(s -> id(generation, s.metadata().getString("chunkId"))).toList(),
                    embeddings, batch);
            marker = embeddings.getFirst();
        }
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
            throw new IllegalStateException("Index attempt interrupted or expired");
        store.addAll(List.of(id(generation, "manifest")), List.of(marker),
                List.of(TextSegment.from("generation complete",
                        Metadata.from(Map.of("generation", generation, "kind", "manifest")))));
    }

    public List<Match> search(List<Scope> scopes, String query, int limit) {
        if (scopes.isEmpty())
            return List.of();
        if (scopes.size() > 5 || query == null || query.isBlank() || query.length() > 500 || limit < 1 || limit > 100)
            throw new IllegalArgumentException("Invalid vector query");
        var embedding = model.embed(query).content();
        var generations = new LinkedHashMap<String, Scope>();
        for (var scope : scopes)
            generations.put(generation(scope), scope);
        var published = new LinkedHashMap<String, Scope>();
        var manifests = store
                .search(EmbeddingSearchRequest.builder().queryEmbedding(embedding).maxResults(generations.size())
                        .filter(Filter.and(metadataKey("generation").isIn(generations.keySet()),
                                metadataKey("kind").isEqualTo("manifest")))
                        .build());
        for (var match : manifests.matches()) {
            String generation = match.embedded().metadata().getString("generation");
            if (generations.containsKey(generation))
                published.put(generation, generations.get(generation));
        }
        if (published.isEmpty())
            return List.of();
        return store
                .search(EmbeddingSearchRequest.builder().queryEmbedding(embedding).maxResults(limit).minScore(minScore)
                        .filter(Filter.and(metadataKey("generation").isIn(published.keySet()),
                                metadataKey("kind").isEqualTo("chunk")))
                        .build())
                .matches().stream().filter(m -> m.embedded() != null)
                .filter(m -> published.containsKey(m.embedded().metadata().getString("generation")))
                .map(m -> new Match(published.get(m.embedded().metadata().getString("generation")),
                        m.embedded().metadata().getString("chunkId")))
                .distinct().toList();
    }
}
