package cn.edu.whu.zhizhida.knowledge;

import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeAnswer;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeAnswerRecord;
import cn.edu.whu.zhizhida.quota.AiRequestQuota;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeQuestionServiceTest {
    @Test void emptyRetrievalAbstainsWithoutQuotaOrProviderCall() {
        var documents=mock(KnowledgeService.class); var ai=mock(KnowledgeAnswerer.class);
        var quota=mock(AiRequestQuota.class); var users=mock(CurrentUserProvider.class);
        var history=mock(KnowledgeAnswerHistory.class);
        var doc=new KnowledgeDocument("owned","笔记","粘贴",1,"原文",null,List.of());
        when(documents.find("owned")).thenReturn(doc);
        when(documents.searchVersions("火星半径",List.of(doc),6)).thenReturn(List.of());
        when(history.save(eq("火星半径"),eq(List.of(doc)),any())).thenAnswer(call->call.getArgument(2));
        var answer=new KnowledgeQuestionService(documents,ai,quota,users,history).ask(new KnowledgeAnswer.Request("火星半径",List.of("owned")));
        assertThat(answer.sufficient()).isFalse(); assertThat(answer.claims()).isEmpty(); assertThat(answer.message()).contains("资料不足");
        verifyNoInteractions(ai,quota,users);
        verify(history).save("火星半径",List.of(doc),answer);
    }

    @Test void followUpUsesQuestionContextAndPinnedVersionButNeverGeneratedClaimsForRetrieval() {
        var documents=mock(KnowledgeService.class); var ai=mock(KnowledgeAnswerer.class);
        var quota=mock(AiRequestQuota.class); var users=mock(CurrentUserProvider.class);
        var history=mock(KnowledgeAnswerHistory.class);
        var chunk=new KnowledgeDocument.Chunk("owned:1",1,"缓存",0,9,1,1,"缓存采用随机过期。");
        var doc=new KnowledgeDocument("owned","笔记","粘贴",1,chunk.content(),null,List.of(chunk));
        var hits=List.of(new KnowledgeDocument.Hit("owned","笔记","粘贴",1,chunk,1));
        var previous=turn("prior","缓存为什么要采用随机过期？",null);
        when(history.find("prior")).thenReturn(previous);
        when(documents.version("owned",1)).thenReturn(doc);
        when(documents.searchVersions("它的适用边界是什么？ 缓存为什么要采用随机过期？",List.of(doc),6)).thenReturn(hits);
        when(users.requireUserId()).thenReturn(1L);
        var insufficient=DefaultKnowledgeAnswerer.insufficient();
        when(ai.answer("它的适用边界是什么？",hits,List.of(previous))).thenReturn(insufficient);
        when(history.save(eq("它的适用边界是什么？"),eq(List.of(doc)),eq(insufficient),eq("prior")))
                .thenReturn(insufficient);

        var answer=new KnowledgeQuestionService(documents,ai,quota,users,history)
                .ask(new KnowledgeAnswer.Request("它的适用边界是什么？",List.of("owned"),"prior"));

        assertThat(answer.sufficient()).isFalse();
        verify(documents).searchVersions("它的适用边界是什么？ 缓存为什么要采用随机过期？",List.of(doc),6);
        verify(documents,never()).find(anyString());
        verify(quota).admit("1",2);
        verify(history).save("它的适用边界是什么？",List.of(doc),insufficient,"prior");
    }

    @Test void explicitNewTopicDoesNotInheritThePreviousTopic() {
        var previous=turn("prior","缓存 TTL 应如何设置？",null);
        assertThat(KnowledgeQuestionService.retrievalQuestion("数据库死锁如何处理？",List.of(previous)))
                .isEqualTo("数据库死锁如何处理？");
        assertThat(KnowledgeQuestionService.retrievalQuestion("它的适用边界是什么？",List.of()))
                .isEqualTo("它的适用边界是什么？");
    }

    @Test void chainedPronounsUseTheNearestExplicitQuestionWithoutGrowingTheQuery() {
        var turns=List.of(turn("one","数据库死锁如何处理？",null),
                turn("two","缓存 TTL 应如何设置？","one"),
                turn("three","它为什么不能设得过长？","two"));
        assertThat(KnowledgeQuestionService.retrievalQuestion("那它的适用边界呢？",turns))
                .isEqualTo("那它的适用边界呢？ 缓存 TTL 应如何设置？");
    }

    @Test void longFollowUpPreservesCurrentQuestionWithinVectorQueryLimit() {
        String question="它"+"问".repeat(299);
        var previous=turn("prior","主题".repeat(150),null);
        String retrieval=KnowledgeQuestionService.retrievalQuestion(question,List.of(previous));
        assertThat(retrieval).hasSize(500).startsWith(question+" ");
        assertThat(retrieval.substring(301)).isEqualTo(previous.question().substring(0,199));
    }

    @Test void truncationNeverSplitsASupplementaryCharacter() {
        String question="它"+"问".repeat(299);
        var split=turn("split","主".repeat(198)+"😀"+"尾".repeat(100),null);
        assertThat(KnowledgeQuestionService.retrievalQuestion(question,List.of(split)))
                .hasSize(499).isEqualTo(question+" "+"主".repeat(198));
        var fits=turn("fits","主".repeat(197)+"😀"+"尾".repeat(101),null);
        assertThat(KnowledgeQuestionService.retrievalQuestion(question,List.of(fits)))
                .hasSize(500).isEqualTo(question+" "+"主".repeat(197)+"😀");
    }

    private KnowledgeAnswerRecord turn(String id,String question,String parent) {
        var answer=new KnowledgeAnswer(true,"历史生成内容",List.of(
                new KnowledgeAnswer.Claim("不应进入检索的历史模型结论",List.of())),"model");
        return new KnowledgeAnswerRecord(id,question,List.of(
                new KnowledgeAnswerRecord.Source("owned","笔记","粘贴",1,true)),answer,Instant.EPOCH,parent);
    }
}
