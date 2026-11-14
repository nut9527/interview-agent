package cn.edu.whu.zhizhida.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.junit.jupiter.api.Test;

import cn.edu.whu.zhizhida.interview.api.KnowledgeSnapshot;
import cn.edu.whu.zhizhida.knowledge.domain.KnowledgeText;

class TrainingKnowledgeCatalogTest {
    @Test
    void archivedExcerptsKeepTheirOriginalChunkIdsAndBoundaries() {
        String text = "# 日志\n\n使用 stderr。\n\nstdout 保留给协议。";
        var old = KnowledgeText.splitLegacy("saved", 1, text).stream().filter(KnowledgeText::isEvidence)
                .map(c -> new KnowledgeSnapshot.Excerpt(c.id(), c.heading(), c.startLine(), c.endLine(), c.content())).toList();
        var snapshot = new KnowledgeSnapshot("saved", "日志", "notes.md", 1,
                cn.edu.whu.zhizhida.common.Sha256.hex(text), text, old);
        var catalog = new TrainingKnowledgeCatalog(mock(KnowledgeService.class));
        var retrieved = catalog.select(List.of(snapshot), "stderr stdout");
        assertThat(retrieved.getFirst().excerpts()).containsExactlyInAnyOrderElementsOf(old);
        assertThat(catalog.verifiedExcerpts(List.of(snapshot), retrieved)).isEqualTo(retrieved);
        assertThat(KnowledgeText.split("saved", text)).noneMatch(c -> old.stream().anyMatch(e -> e.chunkId().equals(c.id())));
    }

    @Test
    void initialExcerptsUsePinnedVersionAndSkipHeadingOnlyFallbacks() {
        var documents = mock(KnowledgeService.class);
        String text = "# 章节\n\n实际正文与边界。";
        var doc = new cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument("saved", "旧标题", "notes.md", 2,
                text, null, KnowledgeText.split("saved", 2, text));
        org.mockito.Mockito.when(documents.find("saved")).thenReturn(doc);
        org.mockito.Mockito.when(documents.searchVersions("未知主题", List.of(doc), 2)).thenReturn(List.of());
        var snapshots = new TrainingKnowledgeCatalog(documents).snapshot(List.of("saved"), "未知主题");
        assertThat(snapshots).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.version()).isEqualTo(2);
            assertThat(snapshot.excerpts()).singleElement().satisfies(e -> assertThat(e.content()).contains("实际正文"));
        });
        org.mockito.Mockito.verify(documents).searchVersions("未知主题", List.of(doc), 2);
    }

    @Test
    void retrievesLateParagraphsOnlyFromArchivedTextWithExactLocationsAndNoFallback() {
        var documents = mock(KnowledgeService.class);
        var catalog = new TrainingKnowledgeCatalog(documents);
        String text = "# 长资料\r\n\r\n" + "普通背景介绍\r\n\r\n".repeat(80)
                + "## TTL\r\n\r\nTTL 90 秒。\r\n\r\n## 写后读\r\n\r\n订单提交后本次请求从主库读取，不承诺后续强一致。\r\n";
        var snapshot = new KnowledgeSnapshot("saved", "旧标题", "notes.md", 1, "saved-hash", text, List.of());
        var sources = catalog.select(List.of(snapshot), "商品过期时间和 read after write 的保证范围");
        assertThat(sources).hasSize(1);
        assertThat(sources.getFirst().sha256()).isEqualTo("saved-hash");
        assertThat(sources.getFirst().excerpts()).extracting(KnowledgeSnapshot.Excerpt::heading).contains("TTL", "写后读");
        assertThat(sources.getFirst().excerpts()).allSatisfy(e -> {
            var chunk = KnowledgeText.split("saved", text).stream().filter(c -> c.id().equals(e.chunkId())).findFirst()
                    .orElseThrow();
            assertThat(e.content()).isEqualTo(text.substring(chunk.startOffset(), chunk.endOffset()));
            assertThat(e.startLine()).isEqualTo(chunk.startLine());
            assertThat(e.endLine()).isEqualTo(chunk.endLine());
        });
        assertThat(catalog.select(List.of(snapshot), "火星半径")).isEmpty();
        verifyNoInteractions(documents);
    }

    @Test
    void onlyCompleteChunksFromTheSelectedImmutableVersionCanBecomeCitationSources() {
        var catalog = new TrainingKnowledgeCatalog(mock(KnowledgeService.class));
        String text = "写入订单后\n\n必须读取主库核对本次写入。";
        String hash = cn.edu.whu.zhizhida.evaluation.EvaluationEngine.hash(text);
        var doc = new KnowledgeSnapshot("saved", "订单", "笔记", 2, hash, text, List.of());
        var result = catalog.select(List.of(doc), "必须读取主库").getFirst();
        var original = result.excerpts().getFirst();
        var prefix = new KnowledgeSnapshot.Excerpt(original.chunkId(), original.heading(), original.startLine(),
                original.endLine(), original.content().substring(0, 4));
        var otherChunk = new KnowledgeSnapshot.Excerpt("another-chunk", original.heading(), original.startLine(),
                original.endLine(), original.content());
        var wrongLocation = new KnowledgeSnapshot.Excerpt(original.chunkId(), original.heading(), 10, 10, original.content());
        var foreign = new KnowledgeSnapshot.Reference("not-selected", doc.title(), 2, hash, result.excerpts());
        var stale = new KnowledgeSnapshot.Reference(doc.id(), doc.title(), 1, hash, result.excerpts());
        var wrongHash = new KnowledgeSnapshot.Reference(doc.id(), doc.title(), 2, "different", result.excerpts());
        var wrongTitle = new KnowledgeSnapshot.Reference(doc.id(), "改名", 2, hash, result.excerpts());
        var altered = new KnowledgeSnapshot.Reference(doc.id(), doc.title(), 2, hash, List.of(prefix, otherChunk, wrongLocation));
        assertThat(catalog.verifiedExcerpts(List.of(doc), List.of(foreign, stale, wrongHash, wrongTitle, altered)))
                .isEmpty();
        assertThat(catalog.verifiedExcerpts(List.of(doc), List.of(altered, result, result))).containsExactly(result);

        var tamperedSnapshot = new KnowledgeSnapshot(doc.id(), doc.title(), doc.sourceName(), doc.version(),
                hash, text + "未归档的补充", List.of());
        assertThat(catalog.verifiedExcerpts(List.of(tamperedSnapshot), List.of(result))).isEmpty();
    }

    @Test
    void preservesFactsAtEndOfChunksAndBoundsTotalContext() {
        var catalog = new TrainingKnowledgeCatalog(mock(KnowledgeService.class));
        String text = ("TTL " + "🙂".repeat(470) + "尾部事实 93 秒\n\n").repeat(12);
        var doc = new KnowledgeSnapshot("saved", "标题", "notes.md", 1, "hash", text, List.of());
        var excerpts = catalog.select(List.of(doc), "TTL").stream().flatMap(r -> r.excerpts().stream()).toList();
        assertThat(excerpts).hasSize(6)
                .allSatisfy(e -> assertThat(e.content()).contains("尾部事实 93 秒").hasSizeLessThanOrEqualTo(1000));
    }
}
