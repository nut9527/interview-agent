package cn.edu.whu.zhizhida.learning;

import cn.edu.whu.zhizhida.learning.api.LearningMemory.Observation;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PracticeMemoryRecallTest {
    private static final Instant NOW=Instant.parse("2026-01-20T00:00:00Z");

    @Test void aBusyCompetencyCannotCrowdOtherCompetenciesOutOfTheContext() {
        var rows=new ArrayList<Observation>();
        for(int i=0;i<30;i++) rows.add(row("storage-"+i,"storage","explanation","session-"+i,1,i));
        rows.add(row("concurrency","concurrency","explanation","older",1,100));
        rows.add(row("reliability","reliability","explanation","older",1,100));
        var selected=PracticeMemoryRecall.select(rows,20);
        assertThat(selected).hasSize(20);
        assertThat(selected.subList(0,3)).extracting(Observation::competencyId)
                .containsExactly("concurrency","reliability","storage");
    }

    @Test void repeatedRoundsDoNotDisplaceEarlierSessionsOrOtherDimensions() {
        var rows=new ArrayList<Observation>();
        for(int round=1;round<=12;round++) rows.add(row("round-"+round,"storage","explanation","latest",round,0));
        rows.add(row("earlier","storage","explanation","previous",1,100));
        rows.add(row("responsibility","storage","responsibility","latest",1,10));
        assertThat(PracticeMemoryRecall.select(rows,20)).extracting(Observation::id)
                .containsExactly("round-12","responsibility","earlier");
    }

    @Test void selectionIsStableAcrossDatabaseOrderAndKeepsRubricsSeparate() {
        var a=row("a","storage","explanation","same",1,0);
        var b=new Observation("b","same","v1",1,"storage","explanation","behavioral-six-v1","real",
                "ACTIVE",1,null,null,NOW);
        assertThat(PracticeMemoryRecall.select(List.of(a,b),20)).containsExactlyElementsOf(
                PracticeMemoryRecall.select(List.of(b,a),20));
        assertThat(PracticeMemoryRecall.select(List.of(a,b),20)).hasSize(2);
        assertThat(PracticeMemoryRecall.select(List.of(),20)).isEmpty();
        assertThat(PracticeMemoryRecall.select(List.of(a),0)).isEmpty();
    }

    static Observation row(String id,String competency,String dimension,String session,int round,int ageSeconds) {
        return new Observation(id,session,"v1",round,competency,dimension,"qualitative-v1","real",
                "ACTIVE",1,null,null,NOW.minusSeconds(ageSeconds));
    }
}
