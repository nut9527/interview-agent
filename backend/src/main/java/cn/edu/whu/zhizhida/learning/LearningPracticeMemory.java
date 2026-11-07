package cn.edu.whu.zhizhida.learning;

import cn.edu.whu.zhizhida.common.MemoryRevokedException;
import cn.edu.whu.zhizhida.learning.api.*;
import cn.edu.whu.zhizhida.learning.api.LearningMemory.Observation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 只读取数量受限、执行模式相同且评价标准兼容的历史观察；用户更正不作为模型指令。 */
@Service
public class LearningPracticeMemory implements PracticeMemoryAccess {
    public static final int MAX_REFERENCES=20;
    private static final Set<String> RUBRICS=Set.of("qualitative-v1","behavioral-six-v1");
    private final LearningMemoryStore store;
    private final PracticeEvidenceSource sources;
    private final boolean enabled;
    public LearningPracticeMemory(LearningMemoryStore store, PracticeEvidenceSource sources,
            @Value("${learning.practice-tools-enabled:false}") boolean enabled) {
        this.store=store; this.sources=sources; this.enabled=enabled;
    }
    @Override @Transactional
    public Selection freeze(long owner,List<String> competencies,String mode,String excludedSession) {
        if(!enabled || !store.personalizationEnabled(owner)) return Selection.none();
        Instant since=Instant.now().minus(180,ChronoUnit.DAYS);
        var scope=competencies.stream().distinct().sorted().toList();
        if(scope.isEmpty() || scope.size()>12) return Selection.none();
        var candidates=store.practiceCandidates(owner,scope,mode,since,excludedSession,MAX_REFERENCES);
        var versions=lockSources(owner,candidates.stream().map(Observation::sourceSessionId).toList());
        var prefs=store.preferences(owner);
        if(!prefs.personalizationEnabled()) return Selection.none();
        versions.forEach((id,version)->store.withdrawChanged(owner,id,version));
        var refs=new ArrayList<Reference>();
        for(var candidate:candidates) for(var row:store.current(owner,candidate.id())) {
            if(eligible(row,scope,mode,since) && Objects.equals(row.sourceVersion(),versions.get(row.sourceSessionId())))
                refs.add(new Reference(row.id(),row.revision(),row.sourceSessionId(),row.sourceVersion()));
        }
        return new Selection(VERSION,mode,since,scope,refs);
    }
    @Override @Transactional
    public List<Observation> read(long owner,Selection selection) { return qualify(owner,selection); }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void lockForCommit(long owner,Selection selection) { qualify(owner,selection); }
    private List<Observation> qualify(long owner,Selection selection) {
        if(!selection.available()) return List.of();
        if(!VERSION.equals(selection.version()) || selection.references().size()>MAX_REFERENCES
                || selection.references().stream().map(Reference::id).distinct().count()!=selection.references().size())
            throw new MemoryRevokedException();
        var versions=lockSources(owner,selection.references().stream().map(Reference::sourceSessionId).toList());
        if(versions.containsValue(null)) throw new MemoryRevokedException();
        // 偏好变更只影响新命令；显式更正或删除还会使旧命令中的相关引用失效。
        store.preferences(owner);
        var result=new ArrayList<Observation>();
        for(var ref:selection.references()) {
            var current=store.current(owner,ref.id());
            if(current.size()!=1) throw new MemoryRevokedException();
            var row=current.getFirst();
            if(row.revision()!=ref.revision() || !row.sourceSessionId().equals(ref.sourceSessionId())
                    || !row.sourceVersion().equals(ref.sourceVersion())
                    || !Objects.equals(row.sourceVersion(),versions.get(row.sourceSessionId()))
                    || !eligible(row,selection.competencies(),selection.executionMode(),selection.since()))
                throw new MemoryRevokedException();
            result.add(row);
        }
        return List.copyOf(result);
    }
    private Map<String,String> lockSources(long owner,List<String> ids) {
        var result=new TreeMap<String,String>();
        ids.stream().distinct().sorted().forEach(id->result.put(id,sources.lock(owner,id)
                .map(s->s.source().version()).orElse(null)));
        return result;
    }
    private boolean eligible(Observation row,List<String> scope,String mode,Instant since) {
        return row.status().equals("ACTIVE") && row.fact()!=null && row.correction()==null
                && scope.contains(row.competencyId()) && mode.equals(row.executionMode())
                && RUBRICS.contains(row.rubricVersion()) && !row.createdAt().isBefore(since)
                && row.fact().evidence().stream().allMatch(e->e.validity().name().equals("VALID"));
    }
}
