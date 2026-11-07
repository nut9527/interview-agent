package cn.edu.whu.zhizhida.learning;

import cn.edu.whu.zhizhida.learning.api.PracticeEvidenceSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每批处理有限的数据库记录，允许崩溃后或多个应用实例间安全重试。 */
@Component
public class LearningObservationWorker {
    private final LearningMemoryStore store;
    private final LearningMemoryService service;
    private final PracticeEvidenceSource source;
    private final boolean enabled;
    private String cursor="";
    public LearningObservationWorker(LearningMemoryStore store,LearningMemoryService service,PracticeEvidenceSource source,
            @Value("${learning.worker-enabled:true}") boolean enabled) {
        this.store=store;this.service=service;this.source=source;this.enabled=enabled;
    }
    @Scheduled(fixedDelay=2000)
    public void poll() {
        if(!enabled) return;
        for(var event:store.pending()) try { service.consume(event); }
        catch(RuntimeException ex) { store.failed(event.id()); }
    }
    @Scheduled(fixedDelay=30000)
    public synchronized void reconcile() {
        if(!enabled) return;
        var refs=source.scan(cursor,50);
        for(var ref:refs) service.reconcile(ref);
        cursor=refs.size()<50?"":refs.getLast().sessionId();
    }
}
