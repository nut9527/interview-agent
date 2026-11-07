package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.interview.api.*;
import cn.edu.whu.zhizhida.evaluation.EvaluationEngine;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class InterviewAssessmentSourcesTest {
    KnowledgeSnapshot document(String text) {
        return new KnowledgeSnapshot("doc", "知识笔记", "notes.md", 2, EvaluationEngine.hash(text), text,
                List.of(new KnowledgeSnapshot.Excerpt("chunk", "事务", 1, 1, text)));
    }
    InterviewTraining training(KnowledgeSnapshot doc) { return new InterviewTraining(InterviewTraining.Topic.DATABASE, null, null, List.of(doc)); }
    @Test void catalogueUsesArchivedExcerptIdentityAndDocumentVersion() {
        var refs = InterviewAssessmentSources.references(7, training(document("唯一键约束重复请求")));
        assertThat(refs).hasSize(1);
        assertThat(refs.getFirst().key()).isEqualTo("knowledge/doc/chunk");
        assertThat(refs.getFirst().version()).startsWith("2@");
        assertThat(refs.getFirst().ownerId()).isEqualTo(7);
        assertThat(refs.getFirst().sha256()).isEqualTo(EvaluationEngine.hash(refs.getFirst().text()));
        assertThat(InterviewAssessmentSources.references(7, null)).isEmpty();
        assertThat(InterviewAssessmentSources.references(7, new InterviewTraining(null, null))).isEmpty();
    }
    @Test void corruptDocumentAndDetachedExcerptCannotBecomeReferences() {
        var d = document("原文");
        assertThatThrownBy(() -> InterviewAssessmentSources.references(7, training(new KnowledgeSnapshot(d.id(),d.title(),d.sourceName(),2,"wrong",d.content(),d.excerpts()))))
                .hasMessageContaining("哈希");
        var detached = new KnowledgeSnapshot(d.id(),d.title(),d.sourceName(),2,d.sha256(),d.content(),
                List.of(new KnowledgeSnapshot.Excerpt("c", "", 1, 1, "其他文档内容")));
        assertThatThrownBy(() -> InterviewAssessmentSources.references(7, training(detached))).hasMessageContaining("片段");
    }
    @Test void duplicateKeysAndOversizedSourcesAreNotSilentlyTruncated() {
        var d = document("原文");
        assertThatThrownBy(() -> InterviewAssessmentSources.references(7, new InterviewTraining(null, null, null, List.of(d, d)))).hasMessageContaining("重复");
        assertThat(InterviewAssessmentSources.references(7, training(document("甲".repeat(2001))))).isEmpty();
        assertThat(InterviewAssessmentSources.references(7, training(document(" ")))).isEmpty();
    }
    @Test void catalogueKeepsSixSourcesAndSixThousandCharacterBound() {
        var docs = new ArrayList<KnowledgeSnapshot>();
        for (int i = 0; i < 8; i++) { var d = document("甲".repeat(1000)); docs.add(new KnowledgeSnapshot("doc"+i,d.title(),d.sourceName(),2,d.sha256(),d.content(),d.excerpts())); }
        var refs = InterviewAssessmentSources.references(7, new InterviewTraining(null,null,null,docs));
        assertThat(refs).hasSize(6);
        assertThat(refs.stream().mapToInt(r -> r.text().length()).sum()).isEqualTo(6000);
    }
}
