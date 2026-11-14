package cn.edu.whu.zhizhida.knowledge;

import cn.edu.whu.zhizhida.ai.api.KnowledgeVectors;
import cn.edu.whu.zhizhida.interview.api.KnowledgeSnapshot;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Hit;
import cn.edu.whu.zhizhida.knowledge.domain.KnowledgeText;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HybridKnowledgeSearchTest {
    @Test void vectorOnlyRecallUsesOriginalTextAndRejectsForeignStaleAndDuplicateHits() {
        var vector = mock(KnowledgeVectors.class); var metrics = new SimpleMeterRegistry();
        var doc = new KnowledgeSnapshot("doc", "资料", "fixture", 2, "hash", "缓存一致性", List.of());
        var chunk = KnowledgeText.split(doc.id(), doc.version(), doc.content()).getFirst();
        var hit = new Hit(doc.id(), doc.title(), doc.sourceName(), doc.version(), chunk, 0);
        var scope = new KnowledgeVectors.Scope("doc", 2, "hash");
        when(vector.search(List.of(scope), "consistency", 30)).thenReturn(List.of(
                new KnowledgeVectors.Match(new KnowledgeVectors.Scope("other-owner", 2, "hash"), chunk.id()),
                new KnowledgeVectors.Match(new KnowledgeVectors.Scope("doc", 1, "hash"), chunk.id()),
                new KnowledgeVectors.Match(new KnowledgeVectors.Scope("doc", 2, "wrong-hash"), chunk.id()),
                new KnowledgeVectors.Match(scope, chunk.id()), new KnowledgeVectors.Match(scope, chunk.id())));
        assertThat(new HybridKnowledgeSearch(vector, metrics).search(List.of(doc), List.of(hit), "consistency", 6)).containsExactly(hit);
    }
    @Test void vectorFailureIsVisibleInsteadOfReportingLexicalFallbackAsHybridSuccess() {
        var vector = mock(KnowledgeVectors.class); var metrics = new SimpleMeterRegistry();
        var doc = new KnowledgeSnapshot("doc", "资料", "fixture", 1, "hash", "cache consistency", List.of());
        var hit = new Hit("doc", "资料", "fixture", 1, KnowledgeText.split("doc", doc.content()).getFirst(), 0);
        when(vector.search(anyList(), anyString(), anyInt())).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(() -> new HybridKnowledgeSearch(vector, metrics).search(List.of(doc), List.of(hit), "cache", 6))
                .isInstanceOf(cn.edu.whu.zhizhida.common.BusinessException.class)
                .hasMessage("资料检索服务暂不可用，请稍后重试。")
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(metrics.get("knowledge.retrieval.failures").counter().count()).isEqualTo(1);
    }

    @Test void oldHeadingOnlyVectorsAndForeignLexicalCandidatesCannotBecomeEvidence() {
        var vector = mock(KnowledgeVectors.class);
        String text = "## 缓存\n\n缓存使用 TTL。";
        var doc = new KnowledgeSnapshot("doc", "资料", "fixture", 1, "hash", text, List.of());
        var chunks = KnowledgeText.split("doc", text);
        var scope = new KnowledgeVectors.Scope("doc", 1, "hash");
        when(vector.search(anyList(), anyString(), anyInt())).thenReturn(chunks.stream()
                .map(c -> new KnowledgeVectors.Match(scope, c.id())).toList());
        var candidates = new java.util.ArrayList<>(chunks.stream()
                .map(c -> new Hit("doc", "资料", "fixture", 1, c, 0)).toList());
        candidates.add(new Hit("foreign", "缓存", "fixture", 1,
                KnowledgeText.split("foreign", "缓存 TTL").getFirst(), 0));
        assertThat(new HybridKnowledgeSearch(vector, new SimpleMeterRegistry()).search(List.of(doc), candidates, "缓存", 6))
                .singleElement().satisfies(h -> {
                    assertThat(h.documentId()).isEqualTo("doc");
                    assertThat(h.chunk().content()).isEqualTo("缓存使用 TTL。");
                });
    }

    @Test void spreadsEvidenceAcrossSectionsThenFillsFromRemainingRelevantChunks() {
        var vector = mock(KnowledgeVectors.class);
        String text = "## 缓存\n\n第一条。\n\n第二条。\n\n第三条。\n\n## 数据库\n\n事务隔离。";
        var doc = new KnowledgeSnapshot("doc", "资料", "fixture", 1, "hash", text, List.of());
        var candidates = KnowledgeText.splitLegacy("doc", 1, text).stream().filter(KnowledgeText::isEvidence)
                .map(c -> new Hit("doc", "资料", "fixture", 1, c, 0)).toList();
        var scope = new KnowledgeVectors.Scope("doc", 1, "hash");
        when(vector.search(anyList(), anyString(), anyInt())).thenReturn(candidates.stream()
                .map(h -> new KnowledgeVectors.Match(scope, h.chunk().id())).toList());
        var search = new HybridKnowledgeSearch(vector, new SimpleMeterRegistry());
        assertThat(search.search(List.of(doc), candidates, "semantic", 3)).extracting(h -> h.chunk().heading())
                .containsExactly("缓存", "缓存", "数据库");
        assertThat(search.search(List.of(doc), candidates, "semantic", 6)).hasSize(4)
                .extracting(h -> h.chunk().content()).containsExactly("第一条。\n", "第二条。\n", "事务隔离。", "第三条。\n");
    }

    @Test void usesConfiguredCandidateBudgetsAndSeparatesRetrievalFromIndexFingerprint() {
        var vector = mock(KnowledgeVectors.class);
        when(vector.retrievalFingerprint()).thenReturn("embedding-v1");
        when(vector.search(anyList(), anyString(), anyInt())).thenReturn(List.of());
        var settings = new KnowledgeRetrievalSettings(20, 1, 50, 1);
        var search = new HybridKnowledgeSearch(vector, new SimpleMeterRegistry(), settings);
        String text = "cache first\n\ncache second";
        var doc = new KnowledgeSnapshot("doc", "资料", "fixture", 1, "hash", text, List.of());
        var hits = KnowledgeText.split("doc", text).stream().map(c -> new Hit("doc", "资料", "fixture", 1, c, 0)).toList();
        assertThat(search.search(List.of(doc), hits, "cache", 6)).hasSize(1);
        verify(vector).search(List.of(new KnowledgeVectors.Scope("doc", 1, "hash")), "cache", 50);
        assertThat(search.fingerprint()).isEqualTo(settings.fingerprint() + ":embedding-v1")
                .isNotEqualTo(new HybridKnowledgeSearch(vector, new SimpleMeterRegistry()).fingerprint());
        assertThatIllegalArgumentException().isThrownBy(() -> new KnowledgeRetrievalSettings(0, 30, 30, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> new KnowledgeRetrievalSettings(60, 101, 30, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> new KnowledgeRetrievalSettings(60, 30, 101, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> new KnowledgeRetrievalSettings(60, 30, 30, 0));
    }
}
