package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.interview.api.InterviewSession.InterviewTurn;

import cn.edu.whu.zhizhida.common.SourceRevokedException;
import cn.edu.whu.zhizhida.interview.api.*;
import cn.edu.whu.zhizhida.evaluation.EvaluationEngine;
import cn.edu.whu.zhizhida.evaluation.api.*;
import cn.edu.whu.zhizhida.evaluation.api.AssessmentResult.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InterviewSourceGuardTest {
    final InterviewSourceAccess access = mock(InterviewSourceAccess.class);
    final InterviewSourceGuard guard = new InterviewSourceGuard(access);
    static final String ANSWER = "我会验证事务结果";
    InterviewSession session() {
        String text = "唯一约束的冲突需核对原请求结果";
        var doc = new KnowledgeSnapshot("doc", "资料", "notes.md", 1, EvaluationEngine.hash(text), text,
                List.of(new KnowledgeSnapshot.Excerpt("chunk", "事务", 1, 1, text)));
        var training = new InterviewTraining(InterviewTraining.Topic.DATABASE, null, null, List.of(doc));
        var items = Arrays.stream(DetailedAssessment.Dimension.values()).map(d -> {
            boolean observed = d == DetailedAssessment.Dimension.TECHNICAL_CORRECTNESS;
            return new DetailedAssessmentDraft.Item(d, observed ? Observation.OBSERVED : Observation.NOT_OBSERVED,
                    "待核对的观察", observed ? ClaimStatus.SUPPORTED : ClaimStatus.INSUFFICIENT, "未独立验证", false,
                    observed ? List.of(new DetailedAssessmentDraft.Citation("answer",0,ANSWER.length(),ANSWER,DetailedAssessmentDraft.Relation.SELF_REPORT),
                            new DetailedAssessmentDraft.Citation("knowledge/doc/chunk",0,4,"唯一约束",DetailedAssessmentDraft.Relation.SUPPORT)) : List.of());
        }).toList();
        var result = new EvaluationEngine().evaluateAnswer(7,"s:answer:1",ANSWER,Instant.now(),
                new AssessmentDraft(null,new DetailedAssessmentDraft("six-dimensions-v1",items)),"controlled", InterviewAssessmentSources.references(7,training));
        var decision = new InterviewAgentState.Decision(InterviewAgentState.Level.PARTIAL, ANSWER,"待验证",InterviewAgentState.Action.FINISH,"storage","依据","练习");
        var agent = new InterviewAgentState("v3","controlled",List.of(new InterviewAgentState.Competency("storage","数据库","事务")),"storage",
                List.of(new InterviewAgentState.Evaluation(1,"storage",decision,null,null,
                        List.of(new KnowledgeSnapshot.Reference(doc.id(),doc.title(),1,doc.sha256(),doc.excerpts())),result)),List.of(),
                List.of(new InterviewAgentState.PracticeMemory("storage",InterviewAgentState.Probe.EVIDENCE,1,0,1)),training);
        return new InterviewSession("s","后端",null,"",InterviewState.FINISHED,1,List.of(new InterviewTurn("user",ANSWER))).withAgent(agent);
    }
    @Test void withdrawalRedactsOnlyReferenceTextAndInvalidatesItsClaimsWithoutMutatingStorage() {
        var original = session();
        when(access.unavailable(7,List.of("doc"))).thenReturn(List.of("doc"));
        var view = guard.view(7,original);
        assertThat(view.sourceRevoked()).isTrue();
        assertThat(view.turns()).isEqualTo(original.turns());
        assertThat(view.agent().training().knowledge().getFirst().content()).isEmpty();
        assertThat(view.agent().training().knowledge().getFirst().excerpts()).isEmpty();
        assertThat(view.agent().practiceMemory()).isEmpty();
        assertThat(view.agent().evaluations().getFirst().knowledgeSources()).isEmpty();
        var detail = view.agent().evaluations().getFirst().assessment().detail();
        assertThat(detail.evidence()).filteredOn(e -> e.sourceType().equals("KNOWLEDGE_EXCERPT")).allSatisfy(e -> {
            assertThat(e.validity()).isEqualTo(Validity.REVOKED); assertThat(e.quote()).isEmpty();
        });
        assertThat(detail.evidence()).filteredOn(e -> e.sourceType().equals("ANSWER_SELF_REPORT")).allSatisfy(e -> assertThat(e.quote()).isEqualTo(ANSWER));
        assertThat(detail.items().get(1).claim().semanticCheck()).isEqualTo("SOURCE_REVOKED");
        assertThat(detail.items().get(1).claim().status()).isEqualTo(ClaimStatus.INSUFFICIENT);
        assertThat(original.agent().training().knowledge().getFirst().content()).isNotEmpty();
        assertThat(original.agent().evaluations().getFirst().assessment().detail().items().get(1).claim().status()).isEqualTo(ClaimStatus.SUPPORTED);
    }
    @Test void formalProjectionKeepsRevocationSignalButNeverAssessmentOrReferenceMaterial() {
        var original = session();
        var formal = new InterviewSession("s","后端",null,"",InterviewState.WAITING_ANSWER,2,original.turns()).withAgent(original.agent())
                .withExperience(new InterviewExperience(InterviewExperience.Format.FORMAL,null,Instant.now()));
        when(access.unavailable(7,List.of("doc"))).thenReturn(List.of("doc"));
        var view = guard.view(7,formal).publicView();
        assertThat(view.sourceRevoked()).isTrue();
        assertThat(view.agent()).isNull();
        assertThat(view.withAgent(null).sourceRevoked()).isTrue();
        assertThat(view.withExperience(formal.experience()).sourceRevoked()).isTrue();
    }
    @Test void revokedSourcesFailUseButRemainReviewableAndValidSourcesKeepOriginalEvidence() {
        var original = session();
        when(access.unavailable(7,List.of("doc"))).thenReturn(List.of("doc"));
        assertThatThrownBy(() -> guard.require(7,original)).isInstanceOf(SourceRevokedException.class);
        assertThat(guard.usable(7,original.agent())).isFalse();
        guard.lock(7,original); verify(access).lockForUse(7,List.of("doc"));
        when(access.unavailable(7,List.of("doc"))).thenReturn(List.of());
        guard.require(7,original);
        var view = guard.view(7,original);
        assertThat(view.sourceRevoked()).isFalse(); assertThat(view.agent()).isSameAs(original.agent());
    }
    @Test void plainInterviewsAndLegacyAssessmentsHaveNoInventedSourceDependencies() {
        var plain = new InterviewSession("plain","后端",null,"",InterviewState.FINISHED,1,List.of());
        when(access.unavailable(7,List.of())).thenReturn(List.of());
        assertThat(InterviewSourceGuard.ids(plain)).isEmpty();
        assertThat(guard.view(7,plain).sourceRevoked()).isFalse();
    }
}
