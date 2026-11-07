package cn.edu.whu.zhizhida.learning;

import cn.edu.whu.zhizhida.learning.api.LearningMemory.*;
import cn.edu.whu.zhizhida.learning.domain.LearningProfileReducer;
import cn.edu.whu.zhizhida.evaluation.api.AssessmentResult.Claim;
import cn.edu.whu.zhizhida.evaluation.api.AssessmentResult.ClaimStatus;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class LearningProfileReducerTest {
    Observation row(String id,String status,String mode,String rubric,cn.edu.whu.zhizhida.evaluation.api.AssessmentResult.Observation observed,ClaimStatus claim) {
        var f=new Fact(1,"storage","CAUSAL_EXPLANATION",rubric,mode,observed,new Claim("claim","提议",List.of(),claim,"MODEL_PROPOSED","尚未验证"),List.of());
        return new Observation(id,"session","version",1,"storage","CAUSAL_EXPLANATION",rubric,mode,status,1,f,null,Instant.EPOCH);
    }
    @Test void unknownDisputedAndModesRemainDistinctAndRevokedDataNeverCounts() {
        var reducer=new LearningProfileReducer();
        var rows=List.of(row("a","ACTIVE","deepseek","v1",cn.edu.whu.zhizhida.evaluation.api.AssessmentResult.Observation.OBSERVED,ClaimStatus.SUPPORTED),
                row("b","ACTIVE","deepseek","v1",cn.edu.whu.zhizhida.evaluation.api.AssessmentResult.Observation.NOT_OBSERVED,ClaimStatus.INSUFFICIENT),
                row("c","ACTIVE","deepseek","v1",cn.edu.whu.zhizhida.evaluation.api.AssessmentResult.Observation.OBSERVED,ClaimStatus.DISPUTED),
                row("d","CORRECTED","deepseek","v1",cn.edu.whu.zhizhida.evaluation.api.AssessmentResult.Observation.OBSERVED,ClaimStatus.SUPPORTED),
                row("e","REVOKED","deepseek","v1",cn.edu.whu.zhizhida.evaluation.api.AssessmentResult.Observation.OBSERVED,ClaimStatus.SUPPORTED),
                row("f","ACTIVE","mock","v1",cn.edu.whu.zhizhida.evaluation.api.AssessmentResult.Observation.NOT_APPLICABLE,ClaimStatus.INSUFFICIENT),
                row("g","ACTIVE","deepseek","v2",cn.edu.whu.zhizhida.evaluation.api.AssessmentResult.Observation.OBSERVED,ClaimStatus.SUPPORTED));
        var p=reducer.reduce(8,rows);assertThat(p.revision()).isEqualTo(8);assertThat(p.groups()).hasSize(3);
        var main=p.groups().stream().filter(g->g.executionMode().equals("deepseek")&&g.rubricVersion().equals("v1")).findFirst().orElseThrow();
        assertThat(main.samples()).isEqualTo(3);assertThat(main.observed()).isEqualTo(2);assertThat(main.unknown()).isEqualTo(1);assertThat(main.disputed()).isEqualTo(1);
        assertThat(main.latestObservationId()).isEqualTo("c");
        assertThat(reducer.reduce(0,List.of()).groups()).isEmpty();
    }
}
