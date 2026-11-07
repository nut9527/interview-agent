package cn.edu.whu.zhizhida.evaluation.api;

import java.util.List;

/** 六个独立的定性维度，不生成总分，也不代表经过独立验证的技术结论。 */
public record DetailedAssessment(String protocolVersion, Rubric rubric,
        List<AssessmentResult.Evidence> evidence, List<Item> items, String limitation) {
    public DetailedAssessment { evidence = List.copyOf(evidence); items = List.copyOf(items); }
    public enum Dimension { EVIDENCE_SUFFICIENCY, TECHNICAL_CORRECTNESS, CAUSAL_EXPLANATION, TRADEOFFS, RESPONSIBILITY, EXPRESSION }
    public record Rubric(String version, List<Anchor> anchors) {
        public Rubric { anchors = List.copyOf(anchors); }
    }
    public record Anchor(Dimension dimension, String behavior) { }
    public record Citation(String evidenceId, DetailedAssessmentDraft.Relation relation) { }
    public record Item(Dimension dimension, AssessmentResult.Observation status, AssessmentResult.Claim claim,
            boolean majorCorrection, String technicalReview, List<Citation> citations) {
        public Item { citations = List.copyOf(citations); }
    }
}
