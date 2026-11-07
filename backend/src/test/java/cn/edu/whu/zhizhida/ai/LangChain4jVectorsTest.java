package cn.edu.whu.zhizhida.ai;

import cn.edu.whu.zhizhida.ai.api.KnowledgeVectors;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LangChain4jVectorsTest {
    @Test void partialGenerationIsInvisibleUntilPublishedAndOtherVersionsStayIsolated() {
        var model = mock(EmbeddingModel.class); var calls = new AtomicInteger();
        when(model.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[]{1, 0})));
        when(model.embedAll(anyList())).thenAnswer(call -> {
            if (calls.incrementAndGet() == 2) throw new IllegalStateException("synthetic failure");
            List<TextSegment> values = call.getArgument(0);
            return Response.from(values.stream().map(v -> Embedding.from(new float[]{1, 0})).toList());
        });
        var store = new InMemoryEmbeddingStore<TextSegment>();
        var vectors = new LangChain4jKnowledgeVectors(model, store, "fixture-v1");
        var scope = new KnowledgeVectors.Scope("doc", 1, "content-sha");
        var chunks = IntStream.range(0, 17).mapToObj(i -> new KnowledgeVectors.Fragment("chunk-" + i, "synthetic " + i)).toList();
        assertThatThrownBy(() -> vectors.index(scope, chunks)).isInstanceOf(IllegalStateException.class);
        assertThat(vectors.search(List.of(scope), "query", 6)).isEmpty();
        vectors.index(scope, chunks);
        assertThat(vectors.search(List.of(scope), "query", 6)).hasSize(6).allMatch(m -> m.scope().equals(scope));
        vectors.index(scope, chunks);
        assertThat(vectors.search(List.of(scope), "query", 30)).hasSize(17);
        assertThat(vectors.search(List.of(scope), "query", 100)).hasSize(17);
        assertThatThrownBy(() -> vectors.search(List.of(scope), "query", 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> vectors.search(List.of(scope), "query", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(vectors.search(List.of(new KnowledgeVectors.Scope("doc", 2, "new-sha")), "query", 6)).isEmpty();
        assertThat(vectors.search(List.of(new KnowledgeVectors.Scope("other", 1, "content-sha")), "query", 6)).isEmpty();
        assertThat(new LangChain4jKnowledgeVectors(model, store, "fixture-v2").search(List.of(scope), "query", 6)).isEmpty();
        var other = new KnowledgeVectors.Scope("other", 1, "content-sha");
        vectors.index(other, chunks);
        vectors.remove(scope, vectors.fingerprint());
        vectors.remove(scope, vectors.fingerprint());
        assertThat(vectors.search(List.of(scope), "query", 6)).isEmpty();
        assertThat(vectors.search(List.of(other), "query", 6)).hasSize(6);
    }
}
