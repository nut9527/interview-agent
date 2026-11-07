package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.common.SourceRevokedException;
import cn.edu.whu.zhizhida.interview.api.*;
import cn.edu.whu.zhizhida.evaluation.api.*;
import cn.edu.whu.zhizhida.evaluation.api.AssessmentResult.*;
import org.springframework.stereotype.Service;
import java.util.*;

/** 每次使用都检查当前授权；对外视图撤回失效引用，但不改写用户回答。 */
@Service
public class InterviewSourceGuard {
    private final InterviewSourceAccess access;
    public InterviewSourceGuard(InterviewSourceAccess access) { this.access = access; }
    public static List<String> ids(InterviewSession session) { return ids(session.agent()); }
    private static List<String> ids(InterviewAgentState agent) {
        return agent == null || agent.training() == null || agent.training().knowledge() == null ? List.of()
                : agent.training().knowledge().stream().map(KnowledgeSnapshot::id).distinct().sorted().toList();
    }
    public boolean usable(long owner, InterviewAgentState agent) { return access.unavailable(owner, ids(agent)).isEmpty(); }
    public void require(long owner, InterviewSession session) {
        if (!access.unavailable(owner, ids(session)).isEmpty()) throw new SourceRevokedException();
    }
    public void lock(long owner, InterviewSession session) { access.lockForUse(owner, ids(session)); }
    public InterviewSession view(long owner, InterviewSession session) {
        var revoked = Set.copyOf(access.unavailable(owner, ids(session)));
        if (revoked.isEmpty()) return session.withSourceRevoked(false);
        var agent = session.agent();
        var training = agent.training();
        var knowledge = training.knowledge().stream().map(d -> revoked.contains(d.id())
                ? new KnowledgeSnapshot(d.id(), d.title(), d.sourceName(), d.version(), d.sha256(), "", List.of()) : d).toList();
        var projected = new InterviewAgentState(agent.version(), agent.executionMode(), agent.plan(), agent.activeCompetencyId(),
                agent.evaluations().stream().map(e -> new InterviewAgentState.Evaluation(e.round(), e.competencyId(), e.decision(),
                        e.answeredProbe(), e.answeredIntent(), e.knowledgeSources() == null ? null : e.knowledgeSources().stream()
                        .filter(r -> !revoked.contains(r.id())).toList(), withdraw(e.assessment(), revoked))).toList(),
                agent.digest(), List.of(), new InterviewTraining(training.topic(), training.retry(), training.question(), knowledge), agent.memoryReferences());
        return session.withAgent(projected).withSourceRevoked(true);
    }
    /** 清空失效来源的引文，并撤回依赖这些证据的结论，避免历史评价继续被误用。 */
    private AssessmentResult withdraw(AssessmentResult result, Set<String> revoked) {
        if (result == null || result.detail() == null) return result;
        var detail = result.detail();
        var invalid = new HashSet<String>();
        var evidence = detail.evidence().stream().map(e -> {
            boolean withdrawn = "KNOWLEDGE_EXCERPT".equals(e.sourceType()) && revoked.stream()
                    .anyMatch(id -> e.sourceId().startsWith("knowledge/" + id + "/"));
            if (!withdrawn) return e;
            invalid.add(e.evidenceId());
            return new Evidence(e.evidenceId(), e.ownerId(), e.sourceType(), e.sourceId(), e.sourceVersion(), e.sourceHash(),
                    e.normalizationVersion(), e.offsetStart(), e.offsetEnd(), "", e.observedAt(), Validity.REVOKED);
        }).toList();
        var items = detail.items().stream().map(i -> i.claim().evidenceIds().stream().noneMatch(invalid::contains) ? i
                : new DetailedAssessment.Item(i.dimension(), i.status(), new Claim(i.claim().claimId(), i.claim().claimText(),
                        i.claim().evidenceIds(), ClaimStatus.INSUFFICIENT, "SOURCE_REVOKED", "对照资料已删除，本项观察已撤销，不再作为新训练依据。"),
                        i.majorCorrection(), "INSUFFICIENT", i.citations())).toList();
        return result.withDetail(new DetailedAssessment(detail.protocolVersion(), detail.rubric(), evidence, items,
                "本场有资料来源已撤销；历史回答保留，相关评价不能再用于训练或画像。"));
    }
}
