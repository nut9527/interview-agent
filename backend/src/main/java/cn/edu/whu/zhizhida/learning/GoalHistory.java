package cn.edu.whu.zhizhida.learning;

import cn.edu.whu.zhizhida.common.UserInputException;

import cn.edu.whu.zhizhida.common.MemoryRevokedException;
import cn.edu.whu.zhizhida.learning.api.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 按用户显式选择，在限定范围内提取目标证据；不改变自动个性化的行为。 */
@Service
public class GoalHistory {
    private final LearningMemoryStore store;
    private final PracticeEvidenceSource sources;
    public GoalHistory(LearningMemoryStore store,PracticeEvidenceSource sources) { this.store=store;this.sources=sources; }
    @Transactional(propagation=Propagation.MANDATORY)
    public List<PracticeMemoryAccess.Reference> freeze(long owner,String competency,List<String> ids) {
        if(ids.isEmpty()) return List.of();
        if(ids.size()>20||ids.stream().distinct().count()!=ids.size()) throw new UserInputException("最多选择 20 条不重复的观察");
        var refs=ids.stream().map(id->store.get(owner,id)).map(r->new PracticeMemoryAccess.Reference(r.id(),r.revision(),r.sourceSessionId(),r.sourceVersion())).toList();
        var rows=qualify(owner,refs);
        if(rows.stream().anyMatch(r->!r.competencyId().equals(competency))) throw new UserInputException("观察超出目标能力范围");
        if(!store.preferences(owner).personalizationEnabled()) throw new UserInputException("请先在学习观察中开启个性化，再显式选择来源");
        return refs;
    }
    /** 使用固定引用重新锁定来源和观察；任一引用失效即拒绝整次使用，不静默替换证据。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public List<LearningMemory.Observation> qualify(long owner,List<PracticeMemoryAccess.Reference> refs) {
        if(refs.isEmpty()) return List.of();
        if(refs.size()>20) throw new MemoryRevokedException();
        var versions=new TreeMap<String,String>();
        refs.stream().map(PracticeMemoryAccess.Reference::sourceSessionId).distinct().sorted().forEach(id->
                versions.put(id,sources.lock(owner,id).map(s->s.source().version()).orElse(null)));
        store.preferences(owner);
        var result=new ArrayList<LearningMemory.Observation>();
        for(var ref:refs) {
            var current=store.current(owner,ref.id());
            if(current.size()!=1) throw new MemoryRevokedException();
            var row=current.getFirst();
            if(row.revision()!=ref.revision()||!row.sourceSessionId().equals(ref.sourceSessionId())
                    ||!row.sourceVersion().equals(ref.sourceVersion())||!Objects.equals(ref.sourceVersion(),versions.get(ref.sourceSessionId()))
                    ||!row.status().equals("ACTIVE")||row.fact()==null||row.correction()!=null
                    ||!row.executionMode().equals("deepseek")||!Set.of("qualitative-v1","behavioral-six-v1").contains(row.rubricVersion())
                    ||row.createdAt().isBefore(Instant.now().minus(180,ChronoUnit.DAYS))
                    ||row.fact().evidence().stream().anyMatch(e->e.ownerId()!=owner||e.validity()!=cn.edu.whu.zhizhida.evaluation.api.AssessmentResult.Validity.VALID))
                throw new MemoryRevokedException();
            result.add(row);
        }
        return List.copyOf(result);
    }
}
