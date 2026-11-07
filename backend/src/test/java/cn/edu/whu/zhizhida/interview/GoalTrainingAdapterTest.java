package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.evaluation.api.AssessmentResult.*;
import cn.edu.whu.zhizhida.interview.api.QuestionCatalog;
import cn.edu.whu.zhizhida.learning.api.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoalTrainingAdapterTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final PracticeEvidenceSource sources=mock(PracticeEvidenceSource.class);
    final GoalSessionLinks goalSessions=mock(GoalSessionLinks.class);
    final GoalTrainingAdapter adapter=new GoalTrainingAdapter(jdbc,mock(QuestionCatalog.class),sources,goalSessions);
    LearningMemory.Fact fact(String dimension,int round,String mode,String rubric,Validity validity) {
        var e=new Evidence("e",1,"ANSWER","s", "v1","hash",Normalization.RAW_UTF16,0,2,"回答",Instant.EPOCH,validity);
        return new LearningMemory.Fact(round,"storage",dimension,rubric,mode,Observation.OBSERVED,
                new Claim("c","观察结论",List.of("e"),ClaimStatus.SUPPORTED,"MODEL_PROPOSED","待本人核对"),List.of(e));
    }
    GoalTraining.Result evaluate(List<LearningMemory.Fact> facts,List<String> dimensions) {
        when(goalSessions.forGoal(1,"g")).thenReturn(List.of(new GoalSessionLinks.Link("g","s","t","q")));
        when(sources.lock(1,"s")).thenReturn(Optional.of(new PracticeEvidenceSource.Snapshot(new PracticeEvidenceSource.SourceRef(1,"s","v1"),facts)));
        return adapter.results(1,"g",dimensions).getFirst();
    }
    @Test void onlyKnownRealCompatibleAndValidEvidenceCounts() {
        for(String mode:List.of("mock","unknown","","real")) assertThat(evaluate(List.of(
                fact("RESPONSIBILITY",1,mode,"qualitative-v1",Validity.VALID),
                fact("CAUSAL_EXPLANATION",1,mode,"qualitative-v1",Validity.VALID)),List.of("RESPONSIBILITY","CAUSAL_EXPLANATION")).supported()).isFalse();
        for(String rubric:List.of("unknown-v9","behavioral-six-v1")) {
            var result=evaluate(List.of(fact("RESPONSIBILITY",1,"deepseek",rubric,Validity.VALID),
                    fact("CAUSAL_EXPLANATION",1,"deepseek",rubric,Validity.VALID)),List.of("RESPONSIBILITY","CAUSAL_EXPLANATION"));
            assertThat(result.supported()).isEqualTo(rubric.equals("behavioral-six-v1"));
            assertThat(result.sourceVersion()).isEqualTo("v1");
        }
        assertThat(evaluate(List.of(fact("RESPONSIBILITY",1,"deepseek","qualitative-v1",Validity.REVOKED),
                fact("CAUSAL_EXPLANATION",1,"deepseek","qualitative-v1",Validity.VALID)),List.of("RESPONSIBILITY","CAUSAL_EXPLANATION")).supported()).isFalse();
    }
    @Test void dimensionsMustBePresentInTheSameRoundAndRubric() {
        var facts=List.of(fact("RESPONSIBILITY",1,"deepseek","qualitative-v1",Validity.VALID),
                fact("CAUSAL_EXPLANATION",2,"deepseek","qualitative-v1",Validity.VALID),
                fact("TRADEOFFS",1,"deepseek","behavioral-six-v1",Validity.VALID));
        assertThat(evaluate(facts,List.of("RESPONSIBILITY","CAUSAL_EXPLANATION")).supported()).isFalse();
        assertThat(evaluate(facts,List.of("RESPONSIBILITY","TRADEOFFS")).supported()).isFalse();
        assertThat(evaluate(List.of(fact("RESPONSIBILITY",1,"deepseek","behavioral-six-v1",Validity.VALID),
                fact("TRADEOFFS",1,"deepseek","behavioral-six-v1",Validity.VALID)),List.of("RESPONSIBILITY","TRADEOFFS")).supported()).isTrue();
    }
}
