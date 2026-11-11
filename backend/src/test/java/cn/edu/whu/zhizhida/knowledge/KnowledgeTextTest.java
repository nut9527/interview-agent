package cn.edu.whu.zhizhida.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.ArrayList;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Hit;

import cn.edu.whu.zhizhida.knowledge.domain.KnowledgeText;

class KnowledgeTextTest {
    @Test
    void preservesCrLfUnicodeOffsetsAndLongParagraphs() {
        String text = "# 标题\r\n\r\n" + "🙂文本".repeat(1000) + "\r\n\r\n## 子标题\r\n内容";
        var rows = KnowledgeText.split("doc", text);
        assertThat(rows).allSatisfy(c -> {
            assertThat(c.content()).isEqualTo(text.substring(c.startOffset(), c.endOffset()));
            assertThat(c.content().length()).isLessThanOrEqualTo(1000);
        });
        assertThat(rows.getLast().heading()).isEqualTo("子标题");
        assertThat(rows.stream().map(c -> c.content().strip()).reduce("", String::concat)).contains("🙂文本".repeat(100));
    }

    @Test
    void questionsProduceChineseAndEnglishRetrievalTerms() {
        assertThat(KnowledgeText.tokens("缓存过期策略是什么？ Redis TTL")).contains("缓存", "过期", "策略", "redis", "ttl")
                .doesNotContain("什么");
    }

    @Test
    void aliasesExpandBidirectionallyWithoutMatchingLatinSubstrings() {
        assertThat(KnowledgeText.tokens("过期时间")).contains("ttl");
        assertThat(KnowledgeText.tokens("TTL")).contains("过期时间");
        assertThat(KnowledgeText.tokens("settle")).doesNotContain("ttl");
        assertThat(KnowledgeText.rank(List.of(hit("doc", "settle")), "TTL", 6)).isEmpty();
    }

    @Test
    void indexesHeadingContextButOnlyReturnsBodyEvidenceWithOriginalOffsets() {
        String text = "# 笔记\r\n\r\n## 空值缓存\r\n\r\n保存 12 秒，创建成功后删除。\r\n";
        var chunks = KnowledgeText.split("doc", text);
        assertThat(chunks).hasSize(3);
        assertThat(chunks.stream().filter(KnowledgeText::isEvidence)).containsExactly(chunks.getLast());
        var body = chunks.getLast();
        assertThat(KnowledgeText.embeddingText(body)).isEqualTo("空值缓存\n" + body.content());
        assertThat(body.content()).isEqualTo(text.substring(body.startOffset(), body.endOffset()));
        var candidates = chunks.stream().map(c -> new cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Hit(
                "doc", "笔记", "fixture", 1, c, 0)).toList();
        assertThat(KnowledgeText.rank(candidates, "空值缓存", 6)).extracting(h -> h.chunk().id())
                .containsExactly(body.id());
    }

    @Test
    void rareTermsOutrankRepeatedCommonTermsAcrossTheCorpus() {
        var candidates = new ArrayList<Hit>();
        candidates.add(hit("common", "redis redis redis redis redis metrics cache storage"));
        candidates.add(hit("specific", "fencing token prevents an expired lease holder from writing"));
        for (int i = 0; i < 8; i++) candidates.add(hit("background-" + i, "redis cache storage metrics"));
        assertThat(KnowledgeText.rank(candidates, "redis fencing", 3).getFirst().documentId()).isEqualTo("specific");
    }

    @Test
    void documentTitleDisambiguatesAnEntityMentionedInAComparison() {
        var primary = new Hit("primary", "北辰 Redis 运维", "fixture", 1,
                KnowledgeText.split("primary", "北辰 Redis 每小时执行一次持久化，保留七天备份；恢复演练逐项核对业务数据。").getFirst(), 0);
        var comparison = new Hit("comparison", "旧项目运维", "fixture", 1,
                KnowledgeText.split("comparison", "Redis 每天持久化，不适用于北辰。").getFirst(), 0);
        assertThat(KnowledgeText.rank(List.of(comparison, primary), "北辰 Redis 持久化", 2)).extracting(Hit::documentId)
                .containsExactly("primary", "comparison");
    }

    @Test
    void normalizesDocumentLengthAndSaturatesRepeatedTerms() {
        var concise = hit("concise", "redis expires keys");
        var verbose = hit("verbose", "redis expires keys " + "background explanation ".repeat(35));
        assertThat(KnowledgeText.rank(List.of(verbose, concise), "redis", 2)).extracting(Hit::documentId)
                .containsExactly("concise", "verbose");
        var one = hit("one", "redis " + "context ".repeat(50));
        var repeated = hit("many", "redis ".repeat(20) + "context ".repeat(35));
        var hits = KnowledgeText.rank(List.of(one, repeated), "redis", 2);
        assertThat(hits.getFirst().score()).isLessThan(hits.getLast().score() * 3);
    }

    private static Hit hit(String id, String text) {
        return new Hit(id, "notes", "fixture", 1, KnowledgeText.split(id, text).getFirst(), 0);
    }
}
