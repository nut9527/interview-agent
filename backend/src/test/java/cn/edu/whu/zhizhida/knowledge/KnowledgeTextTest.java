package cn.edu.whu.zhizhida.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

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
        assertThat(KnowledgeText.score("settle", "TTL")).isZero();
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
}
