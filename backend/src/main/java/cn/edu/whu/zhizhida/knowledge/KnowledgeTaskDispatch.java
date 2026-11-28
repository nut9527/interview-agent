package cn.edu.whu.zhizhida.knowledge;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;

import cn.edu.whu.zhizhida.messaging.RocketTransport;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

/** 以数据库任务行记录待投递工作，通知可重复发送；消息丢失后仍可扫描恢复。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "knowledge.dispatch.mode", havingValue = "rocketmq")
public class KnowledgeTaskDispatch {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KnowledgeTaskDispatch.class);
    private final KnowledgeImportRepository imports;
    private final KnowledgeImportExecutor executor;
    private final KnowledgeIndexRepository indexes;
    private final ObjectProvider<KnowledgeIndexWorker> indexWorker;
    private final RocketTransport transport;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final Environment environment;
    private final boolean publisher, indexEnabled, noticeRecovery;
    private volatile double pending = Double.NaN, oldest = Double.NaN;
    private long reconcileAfter;

    public KnowledgeTaskDispatch(
            KnowledgeImportRepository imports,
            KnowledgeImportExecutor executor,
            KnowledgeIndexRepository indexes,
            ObjectProvider<KnowledgeIndexWorker> indexWorker,
            RocketTransport transport,
            ObjectMapper json,
            MeterRegistry metrics,
            Environment env) {
        this.imports = imports;
        this.executor = executor;
        this.indexes = indexes;
        this.indexWorker = indexWorker;
        this.transport = transport;
        this.json = json;
        this.metrics = metrics;
        environment = env;
        publisher = env.getProperty("knowledge.dispatch.publisher-enabled", Boolean.class, true);
        // 分进程部署由 API 扫描任务表并补发通知，Worker 可依赖该扫描恢复任务。
        noticeRecovery = publisher || "worker".equals(env.getProperty("app.runtime.role", "all"));
        indexEnabled = env.getProperty("agent.vector.enabled", Boolean.class, false);
        metrics.gauge("knowledge.import.pending", this, v -> v.pending);
        metrics.gauge("knowledge.import.oldest.pending.seconds", this, v -> v.oldest);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startConsumers() {
        if (environment.getProperty("knowledge.import.worker-enabled", Boolean.class, true)) {
            transport.listen(
                    "import-workers",
                    List.of("knowledge-import"),
                    environment.getProperty("knowledge.import.concurrency", Integer.class, 2),
                    delivery -> receive("import", delivery));
        }
        if (indexEnabled
                && environment.getProperty("agent.vector.worker-enabled", Boolean.class, true)) {
            transport.listen(
                    "index-workers",
                    List.of("knowledge-index"),
                    environment.getProperty("knowledge.index.concurrency", Integer.class, 1),
                    delivery -> receive("index", delivery));
        }
    }

    @EventListener
    public void archived(KnowledgeRepository.Archived event) {
        if (indexEnabled)
            indexes.enqueueVersion(event.id(), event.version(), indexWorker.getObject().profile());
    }

    @Scheduled(scheduler = "publisherScheduler", fixedDelayString = "${knowledge.dispatch.poll-ms:1000}", initialDelayString = "${knowledge.dispatch.initial-delay-ms:0}")
    public void publish() {
        if (!publisher)
            return;
        try {
            pending = imports.pendingCount();
            oldest = imports.oldestSeconds();
            // 每轮最多各发一条导入和索引通知，避免一类任务占满发布线程。
            // 预留通知会推迟任务的下次投递时间，到期后可为仍待领取的任务补发通知。
            for (String id : imports.pending())
                if (imports.reserveNotice(id)) {
                    send("import", id, Map.of("id", id, "fileRef", imports.reference(id)));
                    break;
                }
            if (indexEnabled) {
                String profile = indexWorker.getObject().profile();
                if (System.nanoTime() >= reconcileAfter) {
                    indexes.reconcile(profile);
                    reconcileAfter = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                }
                for (long id : indexes.pending(profile, true))
                    if (indexes.reserveNotice(id)) {
                        send("index", Long.toString(id), Map.of("id", Long.toString(id)));
                        break;
                    }
            }
        } catch (RuntimeException ex) {
            pending = Double.NaN;
            oldest = Double.NaN;
            metrics.counter("knowledge.dispatch.poll", "outcome", "unavailable").increment();
            log.warn(
                    "event=knowledge.dispatch.poll outcome=unavailable errorType={}",
                    ex.getClass().getSimpleName());
        }
    }

    private void send(String kind, String id, Map<String, String> payload) {
        var sample = io.micrometer.core.instrument.Timer.start(metrics);
        String outcome = "confirmed";
        try {
            transport.send("knowledge-" + kind, kind + ":" + id, json.writeValueAsBytes(payload));
        } catch (RuntimeException ex) {
            outcome = "unavailable";
        } finally {
            metrics.counter("knowledge.dispatch.publish", "kind", kind, "outcome", outcome)
                    .increment();
            sample.stop(metrics.timer("knowledge.dispatch.publish.duration", "kind", kind));
            if (!"confirmed".equals(outcome))
                log.warn(
                        "event=knowledge.dispatch.publish kind={} taskId={} outcome={}",
                        kind,
                        id,
                        outcome);
        }
    }

    boolean receive(String kind, RocketTransport.Delivery message) {
        if (message.body().length > 1024)
            return invalid(kind, message, "payload-size");
        tools.jackson.databind.JsonNode body;
        try {
            body = json.readTree(message.body());
        } catch (RuntimeException ex) {
            return invalid(kind, message, "invalid-json");
        }
        if (body == null || !body.path("id").isString())
            return invalid(kind, message, "task-id");
        String id = body.path("id").asString();
        if (!message.keys().equals(List.of(kind + ":" + id)))
            return invalid(kind, message, "business-key");
        long indexId = 0;
        if (kind.equals("import")) {
            if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                return invalid(kind, message, "task-id");
            if (!body.path("fileRef").isString()
                    || !(id + "/source.upload").equals(body.path("fileRef").asString()))
                return invalid(kind, message, "file-ref");
        } else {
            try {
                indexId = Long.parseLong(id);
            } catch (NumberFormatException ex) {
                return invalid(kind, message, "task-id");
            }
            if (indexId <= 0 || !id.equals(Long.toString(indexId)))
                return invalid(kind, message, "task-id");
        }
        try {
            if (kind.equals("import"))
                executor.execute(id);
            else
                indexWorker.getObject().execute(indexId);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException ex) {
            boolean deferred = noticeRecovery && !Thread.currentThread().isInterrupted();
            String outcome = deferred ? "deferred" : "retry";
            metrics.counter("knowledge.dispatch.delivery", "kind", kind, "outcome", outcome)
                    .increment();
            log.warn(
                    "event=knowledge.dispatch.delivery kind={} taskId={} outcome={} errorType={}",
                    kind,
                    id,
                    outcome,
                    ex.getClass().getSimpleName());
            // 有恢复扫描时可以确认原消息，由任务表补发。否则保留消息，等待 Broker 重投。
            return deferred;
        }
        if (Thread.currentThread().isInterrupted())
            return false;
        metrics.counter("knowledge.dispatch.delivery", "kind", kind, "outcome", "processed")
                .increment();
        return true;
    }

    private boolean invalid(String kind, RocketTransport.Delivery message, String reason) {
        metrics.counter("knowledge.dispatch.delivery", "kind", kind, "outcome", "invalid")
                .increment();
        log.warn(
                "event=knowledge.dispatch.delivery kind={} outcome=invalid reason={}",
                kind,
                reason);
        transport.quarantine(message, reason);
        return true;
    }
}
