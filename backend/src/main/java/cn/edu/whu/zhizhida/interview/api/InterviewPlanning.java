package cn.edu.whu.zhizhida.interview.api;

import java.util.List;
import cn.edu.whu.zhizhida.interview.api.InterviewAgentState.Probe;

/** 每轮选定的验证目标与引用依据；历史多候选记录保持原样。 */
public record InterviewPlanning() {
    public enum Dimension { EVIDENCE_SUFFICIENCY, TECHNICAL_CORRECTNESS, CAUSAL_EXPLANATION, TRADEOFFS, RESPONSIBILITY, EXPRESSION }
    public enum Scenario { CURRENT, FAILURE, ALTERNATIVE, NEW_CONTEXT, MEASUREMENT }
    public enum GapStatus { NOT_OBSERVED, DISPUTED, NEEDS_VERIFICATION, NOT_APPLICABLE }
    public record Focus(Dimension dimension, Scenario scenario, String claimToVerify, String expectedEvidence) { }
    public record QuestionIntent(String competencyId, Probe probe, Dimension dimension, Scenario scenario,
            String claimToVerify, String expectedEvidence, String quote, int offsetStart, int offsetEnd,
            InterviewDifficulty effectiveDifficulty, String question) { }
    public record EvidenceGap(String gapId, Dimension dimension, GapStatus status, String target,
            String sourceId, String sourceVersion, String normalizationVersion, int offsetStart, int offsetEnd, String quote) { }
    public record Candidate(String intentKey, QuestionIntent intent, EvidenceGap gap, int priority) { }
    public record PlanRevision(int revision, int afterRound, String policyVersion, InterviewDifficulty baseDifficulty,
            List<Candidate> candidates, List<String> rejectedReasons, String selectedIntentKey,
            boolean fallback, String reason) {
        public PlanRevision { candidates = List.copyOf(candidates); rejectedReasons = List.copyOf(rejectedReasons); }
    }
}
