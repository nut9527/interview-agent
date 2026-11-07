package cn.edu.whu.zhizhida.interview.api;

import java.util.List;

/** 对外公开、基于证据的检查点；只包含决策结果，不包含模型内部推理。 */
public record InterviewAgentState(
        String version, String executionMode, List<Competency> plan,
        String activeCompetencyId, List<Evaluation> evaluations, List<DigestEntry> digest, List<PracticeMemory> practiceMemory,
        InterviewTraining training, List<MemoryReference> memoryReferences,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        List<InterviewPlanning.PlanRevision> planningRevisions) {
    public InterviewAgentState {
        plan = List.copyOf(plan);
        evaluations = List.copyOf(evaluations);
        digest = digest == null ? List.of() : List.copyOf(digest);
        practiceMemory = practiceMemory == null ? List.of() : List.copyOf(practiceMemory);
        memoryReferences = memoryReferences == null ? List.of() : List.copyOf(memoryReferences);
        planningRevisions = planningRevisions == null ? List.of() : List.copyOf(planningRevisions);
    }

    public InterviewAgentState(String version, String executionMode, List<Competency> plan,
            String activeCompetencyId, List<Evaluation> evaluations, List<DigestEntry> digest,
            List<PracticeMemory> memory, InterviewTraining training, List<MemoryReference> references) {
        this(version, executionMode, plan, activeCompetencyId, evaluations, digest, memory, training, references, List.of());
    }

    public InterviewAgentState(String version, String executionMode, List<Competency> plan,
            String activeCompetencyId, List<Evaluation> evaluations, List<DigestEntry> digest,
            List<PracticeMemory> practiceMemory, InterviewTraining training) {
        this(version,executionMode,plan,activeCompetencyId,evaluations,digest,practiceMemory,training,List.of());
    }

    /** 仅含标识的来源记录在私有回执清理后仍保留，但不代表来源当前仍然有效。 */
    public record MemoryReference(String observationId,long revision,String sourceSessionId,String sourceVersion,
            int sourceRound,String competencyId,String dimension,String rubricVersion,String executionMode,int usedAtRound) { }
    public InterviewAgentState withMemoryReferences(List<MemoryReference> added) {
        var merged=new java.util.LinkedHashMap<String,MemoryReference>();
        for(var r:memoryReferences) merged.put(r.usedAtRound()+"/"+r.observationId(),r);
        for(var r:added) merged.put(r.usedAtRound()+"/"+r.observationId(),r);
        return new InterviewAgentState(version,executionMode,plan,activeCompetencyId,evaluations,digest,practiceMemory,training,List.copyOf(merged.values()),planningRevisions);
    }

    public InterviewAgentState(String version, String executionMode, List<Competency> plan, String activeCompetencyId, List<Evaluation> evaluations) {
        this(version, executionMode, plan, activeCompetencyId, evaluations, List.of(), List.of());
    }

    public InterviewAgentState(String version, String executionMode, List<Competency> plan, String activeCompetencyId,
            List<Evaluation> evaluations, List<DigestEntry> digest, List<PracticeMemory> memory) {
        this(version, executionMode, plan, activeCompetencyId, evaluations, digest, memory, null);
    }

    public enum AnchorKind { PROJECT, INTERNSHIP, SKILLS, JOB, SUMMARY }
    /** 引文是不可信材料，不能作为指令或纳入控制目标。 */
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
    public record Evaluation(int round, String competencyId, Decision decision, Probe answeredProbe, String answeredIntent,
            List<KnowledgeSnapshot.Reference> knowledgeSources,
            cn.edu.whu.zhizhida.evaluation.api.AssessmentResult assessment) {
        public Evaluation { knowledgeSources=knowledgeSources==null?null:List.copyOf(knowledgeSources); }
        public Evaluation(int round, String competencyId, Decision decision, Probe answeredProbe, String answeredIntent,
                List<KnowledgeSnapshot.Reference> knowledgeSources) {
            this(round, competencyId, decision, answeredProbe, answeredIntent, knowledgeSources, null);
        }
        public Evaluation(int round, String competencyId, Decision decision, Probe answeredProbe, String answeredIntent) {
            this(round,competencyId,decision,answeredProbe,answeredIntent,null);
        }
        public Evaluation(int round, String competencyId, Decision decision) { this(round, competencyId, decision, null, null); }
    }
    public InterviewAgentState withKnowledgeSources(int round,List<KnowledgeSnapshot.Reference> sources) {
        return new InterviewAgentState(version,executionMode,plan,activeCompetencyId,evaluations.stream()
                .map(e->e.round()==round?new Evaluation(e.round(),e.competencyId(),e.decision(),e.answeredProbe(),e.answeredIntent(),sources,e.assessment()):e).toList(),
                digest,practiceMemory,training,memoryReferences,planningRevisions);
    }
    public InterviewAgentState withAssessment(int round, cn.edu.whu.zhizhida.evaluation.api.AssessmentResult assessment) {
        return new InterviewAgentState(version,executionMode,plan,activeCompetencyId,evaluations.stream()
                .map(e->e.round()==round?new Evaluation(e.round(),e.competencyId(),e.decision(),e.answeredProbe(),e.answeredIntent(),e.knowledgeSources(),assessment):e).toList(),
                digest,practiceMemory,training,memoryReferences,planningRevisions);
    }
    public record DigestEntry(int round, String competencyId, Level level, String evidence, String gap, Probe answeredProbe) { }
    public record PracticeMemory(String competencyId, Probe probe, int attempts, int insufficient, int partial) { }

    /** 回放较早的回答时，不得暴露后续轮次的评价。 */
    public InterviewAgentState through(int answered) {
        var visible = evaluations.stream().filter(e -> e.round() <= answered).toList();
        String active = visible.isEmpty() ? plan.get(0).id()
                : visible.get(visible.size() - 1).decision().nextCompetencyId();
        return new InterviewAgentState(version, executionMode, plan, active, visible,
                digest.stream().filter(e -> e.round() <= answered).toList(), practiceMemory, training,
                memoryReferences.stream().filter(r->r.usedAtRound()<=answered).toList(),
                planningRevisions.stream().filter(r->r.afterRound()<=answered).toList());
    }
}
