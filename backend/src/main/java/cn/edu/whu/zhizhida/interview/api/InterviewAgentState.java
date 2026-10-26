package cn.edu.whu.zhizhida.interview.api;

import java.util.List;

/** Public, evidence-based checkpoint. Contains decisions, never private model reasoning. */
public record InterviewAgentState(
        String version, String executionMode, List<Competency> plan,
        String activeCompetencyId, List<Evaluation> evaluations, List<DigestEntry> digest, List<PracticeMemory> practiceMemory) {
    public InterviewAgentState {
        plan = List.copyOf(plan);
        evaluations = List.copyOf(evaluations);
        digest = digest == null ? List.of() : List.copyOf(digest);
        practiceMemory = practiceMemory == null ? List.of() : List.copyOf(practiceMemory);
    }

    public InterviewAgentState(String version, String executionMode, List<Competency> plan, String activeCompetencyId, List<Evaluation> evaluations) {
        this(version, executionMode, plan, activeCompetencyId, evaluations, List.of(), List.of());
    }

    public enum AnchorKind { PROJECT, INTERNSHIP, SKILLS, JOB, SUMMARY }
    /** Untrusted quotations, never instructions or part of the control objective. */
    public record MaterialAnchor(AnchorKind kind, String source, String quote) { }
    public record Competency(String id, String name, String objective, List<MaterialAnchor> materialAnchors) {
        public Competency { materialAnchors = materialAnchors == null ? List.of() : List.copyOf(materialAnchors); }
        public Competency(String id, String name, String objective) { this(id, name, objective, List.of()); }
    }
    public enum Level { INSUFFICIENT, PARTIAL, STRONG }
    public enum Action { FOLLOW_UP, ADVANCE, FINISH }
    public enum Probe { OPENING, EVIDENCE, QUANTIFY, COUNTEREXAMPLE, TRANSFER, TRADEOFF, CLARIFY, NONE }
    public record Decision(Level level, String evidence, String gap, Action action,
                           String nextCompetencyId, String reason, String practice, Probe probe, String intent, String questionCompetencyId) {
        public Decision(Level level, String evidence, String gap, Action action, String nextCompetencyId,
                        String reason, String practice, Probe probe, String intent) {
            this(level, evidence, gap, action, nextCompetencyId, reason, practice, probe, intent, null);
        }
        public Decision(Level level, String evidence, String gap, Action action, String nextCompetencyId,
                        String reason, String practice) {
            this(level, evidence, gap, action, nextCompetencyId, reason, practice, null, null, null);
        }
    }
    public record Evaluation(int round, String competencyId, Decision decision, Probe answeredProbe, String answeredIntent) {
        public Evaluation(int round, String competencyId, Decision decision) { this(round, competencyId, decision, null, null); }
    }
    public record DigestEntry(int round, String competencyId, Level level, String evidence, String gap, Probe answeredProbe) { }
    public record PracticeMemory(String competencyId, Probe probe, int attempts, int insufficient, int partial) { }

    /** Replaying an earlier answer must not expose later evaluations. */
    public InterviewAgentState through(int answered) {
        var visible = evaluations.stream().filter(e -> e.round() <= answered).toList();
        String active = visible.isEmpty() ? plan.get(0).id()
                : visible.get(visible.size() - 1).decision().nextCompetencyId();
        return new InterviewAgentState(version, executionMode, plan, active, visible,
                digest.stream().filter(e -> e.round() <= answered).toList(), practiceMemory);
    }
}
