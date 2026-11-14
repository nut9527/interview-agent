package cn.edu.whu.zhizhida.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Hit;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning.*;
import cn.edu.whu.zhizhida.knowledge.domain.KnowledgeText;
import cn.edu.whu.zhizhida.quota.AiRequestQuota;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KnowledgeLearningServiceTest {
    @Test
    void focusedQuizUsesSharedRetrievalOnPinnedVersion() {
        var documents = mock(KnowledgeService.class);
        var generator = mock(KnowledgeQuizGenerator.class);
        var records = mock(KnowledgeLearningRecords.class);
        var quota = mock(AiRequestQuota.class);
        var users = mock(CurrentUserProvider.class);
        String content = "# 请求合并\n\n同键只允许一个加载者回源。\n\n其他请求等待共享结果。";
        var doc = new KnowledgeDocument("book", "资料", "book.md", 2, content, null,
                KnowledgeText.split("book", 2, content));
        var seed = new Hit(doc.id(), doc.title(), doc.sourceName(), 2, doc.chunks().get(1), 0);
        var request = new Generate(List.of(new Selection("book", 2, List.of())), 2,
                Intent.COMPREHENSIVE, Difficulty.INTERMEDIATE, "防止并发击穿");
        when(documents.version("book", 2)).thenReturn(doc);
        when(documents.searchVersions(request.focus(), List.of(doc), 18)).thenReturn(List.of(seed));
        when(users.requireUserId()).thenReturn(1L);
        when(generator.generate(anyList(), eq(request))).thenReturn(new KnowledgeQuizGenerator.Result(List.of(), "mock"));
        when(records.store(anyList())).thenReturn(List.of());

        new KnowledgeLearningService(documents, generator, records, quota, users).generate(request);

        verify(documents).searchVersions(request.focus(), List.of(doc), 18);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<Hit>> evidence = ArgumentCaptor.forClass(List.class);
        verify(generator).generate(evidence.capture(), eq(request));
        assertThat(evidence.getValue()).singleElement().satisfies(h -> {
            assertThat(h.version()).isEqualTo(2);
            assertThat(h.chunk().content()).contains("一个加载者回源", "共享结果");
        });
        verify(quota).admit("1", 2);
    }
}
