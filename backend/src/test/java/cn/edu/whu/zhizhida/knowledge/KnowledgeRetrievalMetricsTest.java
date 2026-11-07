package cn.edu.whu.zhizhida.knowledge;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class KnowledgeRetrievalMetricsTest {
    @Test void computesRankingAndPenalizesDuplicateEvidenceAtItsOriginalPosition() {
        var score = KnowledgeRetrievalMetrics.at(List.of("a", "b"), List.of("irrelevant", "a", "a", "b"), 4);
        assertThat(score.recall()).isEqualTo(1);
        assertThat(score.reciprocalRank()).isEqualTo(0.5);
        double expected = (1 / (Math.log(3) / Math.log(2)) + 1 / (Math.log(5) / Math.log(2)))
                / (1 + 1 / (Math.log(3) / Math.log(2)));
        assertThat(score.ndcg()).isCloseTo(expected, within(0.000001));
        assertThat(KnowledgeRetrievalMetrics.at(List.of("a", "b"), List.of("a", "a", "b"), 2).recall())
                .isEqualTo(0.5);
    }

    @Test void separatesUnanswerableCasesFromRankingAverages() {
        var report = KnowledgeRetrievalMetrics.summarize(List.of(
                new KnowledgeRetrievalMetrics.Sample("direct", List.of("a"), List.of("a")),
                new KnowledgeRetrievalMetrics.Sample("insufficient", List.of(), List.of("a")),
                new KnowledgeRetrievalMetrics.Sample("insufficient", List.of(), List.of())), List.of(1));
        assertThat(report).containsEntry("positiveCases", 1).containsEntry("noAnswerCases", 2L)
                .containsEntry("emptyRetrievalRateOnNoAnswer", 0.5);
        assertThat(report.get("atK")).isEqualTo(Map.of("1", Map.of("recall", 1.0, "mrr", 1.0, "ndcg", 1.0)));
        var noLabels = KnowledgeRetrievalMetrics.summarize(List.of(
                new KnowledgeRetrievalMetrics.Sample("insufficient", List.of(), List.of("a"))), List.of(6));
        assertThat(noLabels).containsEntry("positiveCases", 0);
        assertThatThrownBy(() -> KnowledgeRetrievalMetrics.at(List.of(), List.of(), 6))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
