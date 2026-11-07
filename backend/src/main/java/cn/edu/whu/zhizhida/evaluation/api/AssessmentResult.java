package cn.edu.whu.zhizhida.evaluation.api;

import java.time.Instant;
import java.util.List;

/** 定性观察结果；引文校验与语义支持、技术正确性的判断相互独立。 */
public record AssessmentResult(String protocolVersion, RubricSnapshot rubric, String executionMode,
        List<Evidence> evidence, List<AssessmentItem> items, String limitation, DetailedAssessment detail) {
    public AssessmentResult { evidence = List.copyOf(evidence); items = List.copyOf(items); }
    public AssessmentResult(String protocolVersion, RubricSnapshot rubric, String executionMode,
            List<Evidence> evidence, List<AssessmentItem> items, String limitation) {
        this(protocolVersion, rubric, executionMode, evidence, items, limitation, null);
    }
    public AssessmentResult withDetail(DetailedAssessment value) {
        return new AssessmentResult(protocolVersion, rubric, executionMode, evidence, items, limitation, value);
    }
    public enum Dimension { RESPONSIBILITY, CAUSAL_EXPLANATION }
    public enum Observation { OBSERVED, NOT_OBSERVED, NOT_APPLICABLE }
    public enum ClaimStatus { SUPPORTED, CONTRADICTED, INSUFFICIENT, DISPUTED }
    public enum Normalization { RAW_UTF16, LF_V1 }
    public enum Validity { VALID, REVOKED }
    public record RubricSnapshot(String version, List<Anchor> anchors) {
        public RubricSnapshot { anchors = List.copyOf(anchors); }
    }
    public record Anchor(Dimension dimension, String behavior) { }
    public record Evidence(String evidenceId, long ownerId, String sourceType, String sourceId,
            String sourceVersion, String sourceHash, Normalization normalizationVersion,
            int offsetStart, int offsetEnd, String quote, Instant observedAt, Validity validity) { }
    public record Claim(String claimId, String claimText, List<String> evidenceIds,
            ClaimStatus status, String semanticCheck, String limitation) {
        public Claim { evidenceIds = List.copyOf(evidenceIds); }
    }
    public record AssessmentItem(Dimension dimension, Observation status, Claim claim) { }
}
