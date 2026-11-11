package cn.edu.whu.zhizhida.learning;

import cn.edu.whu.zhizhida.common.UserInputException;

import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.common.*;
import cn.edu.whu.zhizhida.learning.api.*;
import cn.edu.whu.zhizhida.learning.api.LearningMemory.*;
import cn.edu.whu.zhizhida.learning.domain.LearningProfileReducer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** 管理学习观察的读取、更正与撤销，并在来源状态变化后重建定性画像。 */
@Service
public class LearningMemoryService {
    private final LearningMemoryStore store;
    private final PracticeEvidenceSource sources;
    private final CurrentUserProvider users;
    private final LearningProfileReducer reducer=new LearningProfileReducer();
    public LearningMemoryService(LearningMemoryStore store,PracticeEvidenceSource sources,CurrentUserProvider users) {
        this.store=store;this.sources=sources;this.users=users;
    }
    @Transactional
    public LearningMemory read(PageRequest page) {
        long owner=users.requireUserId();
        var recent=store.recent(owner);
        var pageRows=store.page(owner,(page.page()-1)*page.size(),page.size());
        var union=new LinkedHashMap<String,Observation>();
        recent.forEach(r->union.put(r.id(),r)); pageRows.forEach(r->union.put(r.id(),r));
        var refreshed=new HashMap<String,Observation>();
        refresh(owner,List.copyOf(union.values())).forEach(r->refreshed.put(r.id(),r));
        var prefs=store.preferences(owner);
        var profile=reducer.reduce(prefs.revision(),recent.stream().map(r->refreshed.get(r.id())).filter(Objects::nonNull).toList());
        store.snapshot(owner,profile);
        return new LearningMemory(prefs,profile,store.sync(owner),pageRows.stream().map(r->refreshed.get(r.id())).filter(Objects::nonNull).toList(),store.count(owner),page.page(),page.size());
    }
    // 先按固定顺序锁定来源，再锁定偏好和观察记录；不跨模型调用持有长事务。
    private List<Observation> refresh(long owner,List<Observation> rows) {
        var versions=new TreeMap<String,String>();
        rows.stream().filter(r->r.status().equals("ACTIVE")||r.status().equals("CORRECTED"))
                .map(Observation::sourceSessionId).distinct().sorted().forEach(id->
                        versions.put(id,sources.lock(owner,id).map(s->s.source().version()).orElse(null)));
        store.preferences(owner);
        versions.forEach((id,version)->store.withdrawChanged(owner,id,version));
        return rows.stream().flatMap(r->store.current(owner,r.id()).stream()).filter(r->!r.status().equals("DELETED")).toList();
    }
    @Transactional
    public Preferences preferences(PreferenceUpdate input) {
        long owner=users.requireUserId(); var current=store.preferences(owner);
        if(current.revision()!=input.expectedRevision()) throw conflict();
        store.preference(owner,input.personalizationEnabled());
        return store.preferences(owner);
    }
    @Transactional
    public Observation correct(String id,Correction input) {
        long owner=users.requireUserId();
        refresh(owner,List.of(store.get(owner,id)));
        var row=store.lockObservation(owner,id);
        if(row.revision()!=input.expectedRevision()||!row.status().equals("ACTIVE")) throw conflict();
        // 用户意见替代旧观察，但不作为新的已验证能力或控制指令。
        store.change(owner,id,"CORRECTED",input.text().trim());
        rebuild(owner); return store.get(owner,id);
    }
    @Transactional
    public void remove(String id,Removal input) {
        long owner=users.requireUserId();
        refresh(owner,List.of(store.get(owner,id)));
        var row=store.lockObservation(owner,id);
        if(row.status().equals("DELETED")) return;
        if(row.revision()!=input.expectedRevision()) throw conflict();
        store.change(owner,id,"DELETED",null); rebuild(owner);
    }
    private void rebuild(long owner) {
        var profile=reducer.reduce(store.preferences(owner).revision(),store.recentCurrent(owner));
        store.snapshot(owner,profile);
    }
    @Transactional
    public LearningMemory.Export export(String cursor) {
        if(cursor==null) cursor="";
        if(!cursor.isEmpty()&&!cursor.matches("[0-9a-f]{64}")) throw new UserInputException("无效导出游标");
        long owner=users.requireUserId();
        var rows=refresh(owner,store.export(owner,cursor));
        boolean more=rows.size()>100; var selected=rows.stream().limit(100).toList();
        return new LearningMemory.Export("learning-memory-v1",Instant.now(),store.preferences(owner),selected,
                more?selected.getLast().id():null,more);
    }
    /** 在同一事务中复核来源、锁定待处理事件并更新观察，使事件重投不会重复生效。 */
    @Transactional
    public void consume(LearningMemoryStore.Event event) {
        var source=sources.lock(event.owner(),event.session());
        store.preferences(event.owner());
        if(!store.lockEvent(event.id())) return;
        if(source.isPresent()) for(var fact:source.get().facts()) store.add(event.owner(),source.get().source(),fact);
        store.withdrawChanged(event.owner(),event.session(),source.map(s->s.source().version()).orElse(null));
        store.processed(event.id()); rebuild(event.owner());
    }
    @Transactional
    public void reconcile(PracticeEvidenceSource.SourceRef ref) {
        // 持有来源锁后重新检查归属，避免过期的扫描结果重建已删除数据。
        var current=sources.lock(ref.owner(),ref.sessionId());
        if (current.isPresent()) store.record(ref.owner(),ref.sessionId(),current.get().source().version());
        else store.withdrawChanged(ref.owner(),ref.sessionId(),null);
    }
    private BusinessException conflict() { return new BusinessException(HttpStatus.CONFLICT,"记录已变化，请刷新后重试。"); }
}
