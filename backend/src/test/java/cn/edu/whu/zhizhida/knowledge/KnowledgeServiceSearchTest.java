package cn.edu.whu.zhizhida.knowledge;

import cn.edu.whu.zhizhida.ai.api.KnowledgeVectors;
import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.common.ResourceNotFoundException;
import cn.edu.whu.zhizhida.common.Sha256;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument;
import cn.edu.whu.zhizhida.knowledge.domain.KnowledgeText;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeServiceSearchTest {
    private final KnowledgeRepository repository = mock(KnowledgeRepository.class);
    private final CurrentUserProvider users = mock(CurrentUserProvider.class);
    private final KnowledgeVectors vectors = mock(KnowledgeVectors.class);

    private KnowledgeService service(boolean enabled) {
        when(users.requireUserId()).thenReturn(7L);
        var beans = new StaticListableBeanFactory();
        if (enabled) beans.addBean("hybrid", new HybridKnowledgeSearch(vectors, new SimpleMeterRegistry()));
        return new KnowledgeService(repository, users, beans.getBeanProvider(HybridKnowledgeSearch.class));
    }

    private KnowledgeDocument doc(int version, String text) {
        return new KnowledgeDocument("owned", "资料", "notes.md", version, text, null,
                KnowledgeText.split("owned", version, text));
    }

    private void semanticMatch(KnowledgeDocument doc, String query) {
        var scope = new KnowledgeVectors.Scope(doc.id(), doc.version(), Sha256.hex(doc.content()));
        when(vectors.search(List.of(scope), query, 30)).thenReturn(List.of(
                new KnowledgeVectors.Match(scope, doc.chunks().getFirst().id())));
    }

    @Test void selectedCurrentDocumentsCanRecallChunksWithoutAnyLexicalOverlap() {
        var service = service(true);
        var doc = doc(2, "事务提交后，本次请求查询主库。");
        when(repository.find(7L, "owned")).thenReturn(Optional.of(doc));
        semanticMatch(doc, "consistency");
        assertThat(service.search("consistency", List.of("owned"), 6)).singleElement().satisfies(h -> {
            assertThat(h.version()).isEqualTo(2);
            assertThat(h.chunk()).isEqualTo(doc.chunks().getFirst());
        });
        verify(repository, never()).candidates(anyLong(), anyList(), anyList());
    }

    @Test void versionSearchReloadsAuthorizedArchiveAndNeverTrustsCallerChunks() {
        var service = service(true);
        var saved = doc(1, "事务提交后，本次请求查询主库。");
        var forged = doc(1, "调用方伪造的证据。");
        when(repository.version(7L, "owned", 1)).thenReturn(Optional.of(saved));
        semanticMatch(saved, "consistency");
        assertThat(service.searchVersions("consistency", List.of(forged), 6)).singleElement()
                .satisfies(h -> assertThat(h.chunk()).isEqualTo(saved.chunks().getFirst()));
        verify(repository, never()).versionCandidates(anyLong(), anyList(), anyList());
    }

    @Test void deletedOrForeignArchivesAreRejectedBeforeVectorAccess() {
        var service = service(true);
        when(repository.version(7L, "owned", 1)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.searchVersions("consistency", List.of(doc(1, "原文")), 6))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(vectors);
    }

    @Test void disabledVectorsAndUnscopedRecommendationsUseLexicalQueries() {
        var doc = doc(1, "cache consistency");
        var hit = new KnowledgeDocument.Hit(doc.id(), doc.title(), doc.sourceName(), 1, doc.chunks().getFirst(), 0);
        when(repository.versionCandidates(eq(7L), eq(List.of(doc)), anyList())).thenReturn(List.of(hit));
        assertThat(service(false).searchVersions("cache", List.of(doc), 6)).hasSize(1);
        when(repository.candidates(eq(7L), eq(List.of()), anyList())).thenReturn(List.of(hit));
        assertThat(service(true).search("cache", List.of(), 6)).hasSize(1);
        verifyNoInteractions(vectors);
    }

    @Test void selectedSearchSurfacesVectorProviderFailure() {
        var service = service(true);
        var doc = doc(1, "cache consistency");
        when(repository.find(7L, "owned")).thenReturn(Optional.of(doc));
        when(vectors.search(anyList(), anyString(), anyInt())).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> service.search("cache", List.of("owned"), 6))
                .isInstanceOf(cn.edu.whu.zhizhida.common.BusinessException.class)
                .hasMessage("资料检索服务暂不可用，请稍后重试。");
    }
}
