package cn.edu.whu.zhizhida.knowledge;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import cn.edu.whu.zhizhida.ai.api.KnowledgeVectors;
import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.common.ResourceNotFoundException;
import cn.edu.whu.zhizhida.knowledge.domain.KnowledgeText;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 执行资料版本的向量建索引与清理任务，协调定时扫描和消息消费的本机并发。
 *
 * <p>
 * 仓库负责跨实例互斥、代际切换和失败重试状态；Worker 负责向量 I/O 与执行指标。
 */
@Service
@ConditionalOnProperty(name = "agent.vector.enabled", havingValue = "true")
public class KnowledgeIndexWorker {
    private final KnowledgeIndexRepository jobs;
    private final KnowledgeRepository documents;
    private final KnowledgeVectors vectors;
    private final CurrentUserProvider users;
    private final MeterRegistry metrics;
    private final boolean enabled;
    private final java.util.concurrent.Semaphore permits;
    private final java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors
            .newSingleThreadExecutor(
                    Thread.ofPlatform().daemon(true).name("knowledge-index").inheritInheritableThreadLocals(false)
                            .factory());

    public KnowledgeIndexWorker(KnowledgeIndexRepository jobs, KnowledgeRepository documents, KnowledgeVectors vectors,
            CurrentUserProvider users, MeterRegistry metrics,
            @Value("${agent.vector.worker-enabled:true}") boolean enabled,
            @Value("${knowledge.index.concurrency:1}") int concurrency) {
        this.jobs = jobs;
        this.documents = documents;
        this.vectors = vectors;
        this.users = users;
        this.metrics = metrics;
        this.enabled = enabled;
        permits = new java.util.concurrent.Semaphore(concurrency);
    }

    @Scheduled(fixedDelayString = "${knowledge.dispatch.index-scan-ms:${agent.vector.poll-ms:5000}}")
    public void tick() {
        if (!enabled || !busy.compareAndSet(false, true))
            return;
        try {
            // 专用线程避免阻塞共享调度器；busy 只合并定时扫描，扫描与消息消费共用 permits 限流。
            executor.execute(() -> {
                try {
                    runOnce();
                } catch (RuntimeException ex) {
                    metrics.counter("knowledge.index.failure", "phase", "discovery").increment();
                } finally {
                    busy.set(false);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            busy.set(false);
        }
    }

    @jakarta.annotation.PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    // API 节点也从向量适配器读取指纹，确保登记任务与执行索引使用同一供应商配置标识。
    public String profile() {
        return vectors.fingerprint();
    }

    public boolean runOnce() {
        if (!permits.tryAcquire())
            return false;
        try {
            String profile = vectors.fingerprint();
            jobs.reconcile(profile);
            return jobs.processNext(profile, action(profile));
        } finally {
            permits.release();
        }
    }

    public boolean execute(long id) throws InterruptedException {
        // 监听器等待扫描释放许可，期间消息保持未确认，也不占用数据库连接。
        permits.acquire();
        try {
            String profile = vectors.fingerprint();
            return jobs.process(id, profile, action(profile));
        } finally {
            permits.release();
        }
    }

    /**
     * 构造当前配置的向量操作，区分建索引、资料删除和旧代际清理。
     *
     * <p>
     * 仓库在同版本新代际就绪后才调度旧代际清理；I/O 异常交回仓库记录重试状态。
     */
    private java.util.function.Consumer<KnowledgeIndexRepository.Job> action(String profile) {
        return job -> {
            var sample = io.micrometer.core.instrument.Timer.start(metrics);
            var scope = new KnowledgeVectors.Scope(job.documentId(), job.version(), job.hash());
            try {
                if (job.owner() == null || !profile.equals(job.profile()))
                    vectors.remove(scope, job.profile());
                else {
                    var doc = documents.version(job.owner(), job.documentId(), job.version());
                    if (doc.isPresent()) {
                        var fragments = doc.get().chunks().stream().filter(KnowledgeText::isEvidence)
                                .map(c -> new KnowledgeVectors.Fragment(c.id(), KnowledgeText.embeddingText(c)))
                                .toList();
                        // 纯标题文档没有证据；完成空索引任务，避免无效 embedding 与永久重试。
                        if (fragments.isEmpty())
                            vectors.remove(scope, profile);
                        else
                            vectors.index(scope, fragments);
                    }
                    // 删除可能与远端写入并发，完成后再次核对；外键墓碑保留账号级联删除后的清理任务。
                    if (documents.version(job.owner(), job.documentId(), job.version()).isEmpty())
                        vectors.remove(scope, profile);
                }
                metrics.counter("knowledge.index.completed").increment();
            } catch (RuntimeException ex) {
                metrics.counter("knowledge.index.failure", "phase", "io").increment();
                throw ex;
            } finally {
                sample.stop(metrics.timer("knowledge.index.execution"));
            }
        };
    }

    public List<Map<String, Object>> status(String id) {
        long owner = owned(id);
        return jobs.status(owner, id);
    }

    public void rebuild(String id) {
        long owner = owned(id);
        jobs.reconcile(vectors.fingerprint());
        jobs.requeue(owner, id, vectors.fingerprint());
    }

    private long owned(String id) {
        long owner = users.requireUserId();
        if (documents.find(owner, id).isEmpty())
            throw new ResourceNotFoundException("资料不存在");
        return owner;
    }
}
