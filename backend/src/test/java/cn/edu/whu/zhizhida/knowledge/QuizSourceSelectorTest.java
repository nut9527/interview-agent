package cn.edu.whu.zhizhida.knowledge;

import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning.*;
import cn.edu.whu.zhizhida.knowledge.domain.KnowledgeText;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class QuizSourceSelectorTest {
    KnowledgeDocument document(String text) {
        return new KnowledgeDocument("book","技术资料","book.md",2,text,null,KnowledgeText.split("book",2,text));
    }
    Generate request(List<String> ids,String focus) {
        return new Generate(List.of(new Selection("book",2,ids)),5,Intent.COMPREHENSIVE,Difficulty.INTERMEDIATE,focus);
    }
    @Test void samplesAcrossTheBookAndSkipsPrefaceAndContents() {
        var doc=document("# 前言\n\n欢迎阅读，本书作者介绍。\n\n# 目录\n\n第一章......1\n第二章......20\n\n"
                +IntStream.range(0,60).mapToObj(i->"# 第"+i+"章\n\n机制"+i+"：这是技术原理及其应用场景与边界。\n\n").reduce("",String::concat));
        var hits=QuizSourceSelector.select(List.of(doc),request(List.of(),""));
        assertThat(hits).hasSize(18);
        assertThat(hits).noneMatch(h->h.chunk().content().contains("欢迎阅读") || h.chunk().content().contains("......"));
        assertThat(hits).anyMatch(h->h.chunk().ordinal()>doc.chunks().size()*0.9);
    }
    @Test void searchesTheFocusDeepInTheBookAndKeepsItsContext() {
        var doc=document("# 开头\n\n这是无关介绍。\n\n# 缓存\n\n缓存穿透采用空值缓存，存活5秒。\n\n需要设置较短有效期，避免数据更新后仍读到空值。\n\n# 索引\n\n索引维护增加写入成本。");
        var hits=QuizSourceSelector.select(List.of(doc),request(List.of(),"缓存穿透"));
        assertThat(hits).singleElement().satisfies(h ->
                assertThat(h.chunk().content()).contains("缓存穿透", "较短有效期"));
        assertThat(hits).noneMatch(h->h.chunk().content().contains("索引维护"));
        assertThat(QuizSourceSelector.select(List.of(doc),request(List.of(),"木星轨道运动"))).isEmpty();
    }
    @Test void explicitScopeIsPreservedAndForeignChunksAreRejected() {
        var doc=document("# 缓存\n\nTTL设置90秒。\n\n空值缓存5秒。");
        String id=doc.chunks().getLast().id();
        assertThat(QuizSourceSelector.select(List.of(doc),request(List.of(id),"TTL")))
                .singleElement().satisfies(h->assertThat(h.chunk().id()).isEqualTo(id));
        assertThatThrownBy(()->QuizSourceSelector.select(List.of(doc),request(List.of("foreign"),"")))
                .isInstanceOf(cn.edu.whu.zhizhida.common.UserInputException.class);
        assertThatThrownBy(()->QuizSourceSelector.select(List.of(doc),request(List.of(doc.chunks().getFirst().id()),"")))
                .isInstanceOf(cn.edu.whu.zhizhida.common.UserInputException.class).hasMessageContaining("只有标题");
    }

    @Test void focusedSearchAcceptsSemanticSeedsAndKeepsArchivedNeighbors() {
        String text = "# 请求合并\n\n同一键只允许一个加载者回源。\n\n其他调用等待共享结果。\n\n# 其他\n\n无关内容。";
        var doc = new KnowledgeDocument("book", "技术资料", "book.md", 2, text, null,
                KnowledgeText.splitLegacy("book", 2, text));
        var seed=doc.chunks().stream().filter(c->c.content().contains("一个加载者")).findFirst().orElseThrow();
        var hits=QuizSourceSelector.select(List.of(doc),request(List.of(),"防止并发击穿"), (snapshot, query, limit)-> {
            assertThat(snapshot).isSameAs(doc);
            assertThat(query).isEqualTo("防止并发击穿");
            return List.of(new cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Hit(
                    doc.id(),doc.title(),doc.sourceName(),doc.version(),seed,0));
        });
        assertThat(hits).hasSize(2).allMatch(h->h.chunk().heading().equals("请求合并"));
        assertThat(hits).anyMatch(h->h.chunk().content().contains("共享结果"));
    }

    @Test void explicitSelectionAndUnfocusedSamplingDoNotInvokeSearch() {
        var doc=document("# 缓存\n\nTTL设置90秒。");
        QuizSourceSelector.FocusSearch forbidden=(snapshot,query,limit)-> { throw new AssertionError("unexpected search"); };
        assertThat(QuizSourceSelector.select(List.of(doc),request(List.of(),""),forbidden)).hasSize(1);
        assertThat(QuizSourceSelector.select(List.of(doc),request(List.of(doc.chunks().getLast().id()),"TTL"),forbidden)).hasSize(1);
    }
}
