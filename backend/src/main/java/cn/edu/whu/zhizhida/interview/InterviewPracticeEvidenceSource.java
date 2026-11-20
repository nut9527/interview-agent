package cn.edu.whu.zhizhida.interview;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cn.edu.whu.zhizhida.common.Sha256;
import cn.edu.whu.zhizhida.common.persistence.JsonColumnCodec;
import cn.edu.whu.zhizhida.evaluation.api.AssessmentResult;
import cn.edu.whu.zhizhida.interview.api.InterviewState;
import cn.edu.whu.zhizhida.learning.api.LearningMemory;
import cn.edu.whu.zhizhida.learning.api.PracticeEvidenceSource;

/** 将已完成面试中的事实转换为学习模块约定的证据结构。 */
@Repository
public class InterviewPracticeEvidenceSource implements PracticeEvidenceSource {
    private final InterviewSessionRepository sessions;
    private final InterviewSourceGuard guard;
    private final JdbcTemplate jdbc;
    private final JsonColumnCodec json;

    public InterviewPracticeEvidenceSource(InterviewSessionRepository sessions, InterviewSourceGuard guard,
            JdbcTemplate jdbc, JsonColumnCodec json) {
        this.sessions = sessions;
        this.guard = guard;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Snapshot> lock(long owner, String id) {
        var session = sessions.findByIdForUpdate(owner, id);
        if (session.isEmpty() || session.get().state() != InterviewState.FINISHED || session.get().agent() == null)
            return Optional.empty();
        var s = session.get();
        if (!guard.usable(owner, s.agent()))
            return Optional.empty();
        guard.lock(owner, s);
        var facts = new ArrayList<LearningMemory.Fact>();
        for (var evaluation : s.agent().evaluations()) {
            var a = evaluation.assessment();
            if (a == null)
                continue; // 旧版等级不能被转换为并不存在的评分维度观察。
            if (a.detail() != null) {
                var d = a.detail();
                for (var item : d.items())
                    facts.add(fact(owner, evaluation.round(), evaluation.competencyId(),
                            item.dimension().name(), d.rubric().version(), a.executionMode(), item.status(),
                            item.claim(), d.evidence()));
            } else
                for (var item : a.items())
                    facts.add(fact(owner, evaluation.round(), evaluation.competencyId(),
                            item.dimension().name(), a.rubric().version(), a.executionMode(), item.status(),
                            item.claim(), a.evidence()));
        }
        return Optional.of(new Snapshot(new SourceRef(owner, id, version(json.write(s.agent()))), facts));
    }

    private LearningMemory.Fact fact(long owner, int round, String competency, String dimension, String rubric,
            String mode,
            AssessmentResult.Observation status, AssessmentResult.Claim claim,
            List<AssessmentResult.Evidence> evidence) {
        var selected = evidence.stream().filter(e -> claim.evidenceIds().contains(e.evidenceId())).toList();
        if (selected.stream().anyMatch(e -> e.ownerId() != owner || e.validity() != AssessmentResult.Validity.VALID)
                || selected.size() != new HashSet<>(claim.evidenceIds()).size())
            throw new IllegalStateException("Invalid learning evidence");
        return new LearningMemory.Fact(round, competency, dimension, rubric, mode, status, claim, selected);
    }

    @Override
    public List<SourceRef> scan(String after, int limit) {
        return jdbc.query("SELECT user_id,session_id,SHA2(agent_json,256) AS version FROM interview_sessions "
                + "WHERE session_id>? AND state='FINISHED' AND agent_json IS NOT NULL ORDER BY session_id LIMIT ?",
                (r, n) -> new SourceRef(r.getLong(1), r.getString(2), r.getString(3)), after, limit);
    }

    public static String version(String text) {
        return Sha256.hex(text);
    }
}
