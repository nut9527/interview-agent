package cn.edu.whu.zhizhida.knowledge;

import cn.edu.whu.zhizhida.ai.api.KnowledgeVectors;
import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument;
import cn.edu.whu.zhizhida.knowledge.domain.KnowledgeText;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeIndexWorkerTest {
    @Test void includesHeadingContextAndSkipsHeadingOnlyFragments() {
        var vectors = index("# 笔记\n\n## 空值缓存\n\n保存 12 秒。\n");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeVectors.Fragment>> fragments = ArgumentCaptor.forClass(List.class);
        verify(vectors).index(eq(new KnowledgeVectors.Scope("doc", 1, "hash")), fragments.capture());
        assertThat(fragments.getValue()).containsExactly(new KnowledgeVectors.Fragment("doc:3", "空值缓存\n保存 12 秒。\n"));
        verify(vectors, never()).remove(any(), anyString());
    }

    @Test void headingOnlyDocumentCompletesWithoutEmbeddingOrRetryingAnEmptyIndex() {
        var vectors = index("# 目录\n\n## 待补充\n");
        verify(vectors, never()).index(any(), anyList());
        verify(vectors).remove(new KnowledgeVectors.Scope("doc", 1, "hash"), "profile");
    }

    private KnowledgeVectors index(String content) {
        var jobs = mock(KnowledgeIndexRepository.class);
        var documents = mock(KnowledgeRepository.class);
        var vectors = mock(KnowledgeVectors.class);
        when(vectors.fingerprint()).thenReturn("profile");
        when(documents.version(1L, "doc", 1)).thenReturn(Optional.of(new KnowledgeDocument(
                "doc", "笔记", "fixture", 1, content, null, KnowledgeText.split("doc", content))));
        when(jobs.processNext(eq("profile"), any())).thenAnswer(call -> {
            Consumer<KnowledgeIndexRepository.Job> action = call.getArgument(1);
            action.accept(new KnowledgeIndexRepository.Job(1, "doc", 1, "hash", "profile", 1L));
            return true;
        });
        var metrics = new SimpleMeterRegistry();
        try {
            var worker = new KnowledgeIndexWorker(jobs, documents, vectors, mock(CurrentUserProvider.class), metrics, false, 1);
            try { assertThat(worker.runOnce()).isTrue(); }
            finally { worker.close(); }
        } finally { metrics.close(); }
        return vectors;
    }
}
