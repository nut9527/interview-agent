package cn.edu.whu.zhizhida.interview.execution;

import java.util.concurrent.TimeUnit;

import org.springframework.scheduling.annotation.Scheduled;

import cn.edu.whu.zhizhida.messaging.RocketTransport;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

public class RunDispatchPublisher {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RunDispatchPublisher.class);
    private final RunDispatchOutbox outbox;
    private final RocketTransport transport;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final int batchSize;
    private final long batchBudgetNanos;
    private volatile double pending = Double.NaN;
    private volatile double oldestPending = Double.NaN;
    private volatile double lastSuccessfulPoll = Double.NaN;

    public RunDispatchPublisher(
            RunDispatchOutbox outbox,
            RocketTransport transport,
            ObjectMapper json,
            MeterRegistry metrics,
            int batchSize,
            long batchBudgetMs) {
        if (batchSize < 1 || batchSize > 32)
            throw new IllegalArgumentException("Outbox batch size must be 1..32");
        if (batchBudgetMs < 1 || batchBudgetMs > 1000)
            throw new IllegalArgumentException("Outbox batch budget must be 1..1000ms");
        this.outbox = outbox;
        this.transport = transport;
        this.json = json;
        this.metrics = metrics;
        this.batchSize = batchSize;
        this.batchBudgetNanos = TimeUnit.MILLISECONDS.toNanos(batchBudgetMs);
        metrics.gauge("zhizhida.outbox.pending", this, value -> value.pending);
        metrics.gauge("zhizhida.outbox.oldest.pending.seconds", this, value -> value.oldestPending);
        metrics.gauge(
                "zhizhida.outbox.last.successful.poll.timestamp.seconds",
                this,
                value -> value.lastSuccessfulPoll);
    }

    @Scheduled(scheduler = "publisherScheduler", fixedDelayString = "${app.outbox.publisher.interval-ms:500}", initialDelayString = "${app.outbox.publisher.initial-delay-ms:0}")
    public void publish() {
        // 逐条领取、发送并写回，避免预占后续记录的租约。批次时间预算只在领取下一条前检查。
        long started = System.nanoTime();
        try {
            for (int sent = 0; sent < batchSize && !Thread.currentThread().isInterrupted(); sent++) {
                if (sent > 0 && System.nanoTime() - started >= batchBudgetNanos)
                    break;
                var delivery = outbox.claim();
                if (delivery.isEmpty() || !send(delivery.get()))
                    break;
            }
            pending = outbox.pending();
            oldestPending = outbox.oldestPendingSeconds();
            lastSuccessfulPoll = System.currentTimeMillis() / 1000.0;
        } catch (RuntimeException ex) {
            // 数据库恢复后由定时任务继续重试；当前积压未知，不能沿用旧值或误报为零。
            pending = Double.NaN;
            oldestPending = Double.NaN;
            metrics.counter("zhizhida.outbox.poll", "outcome", "unavailable").increment();
            log.warn(
                    "event=run.outbox.poll outcome=unavailable errorType={}",
                    ex.getClass().getSimpleName());
        }
    }

    boolean send(RunDispatchOutbox.Delivery delivery) {
        var sample = io.micrometer.core.instrument.Timer.start(metrics);
        String error = null;
        try {
            transport.send(
                    "run-ready",
                    delivery.message().eventId(),
                    json.writeValueAsBytes(delivery.message()));
        } catch (RuntimeException ex) {
            error = Thread.currentThread().isInterrupted() ? "INTERRUPTED" : "PUBLISH_UNAVAILABLE";
        }
        // 发布令牌限定可写回的记录。若收到回执后写回失败，租约到期时同一事件可能再次发送。
        if (error == null)
            outbox.published(delivery);
        else {
            log.warn(
                    "event=run.outbox.publish outcome={} runId={} eventId={} attempt={}",
                    error,
                    delivery.message().runId(),
                    delivery.message().eventId(),
                    delivery.attempts());
            outbox.retry(delivery, error);
        }
        metrics.counter("zhizhida.outbox.publish", "outcome", error == null ? "confirmed" : error)
                .increment();
        sample.stop(
                metrics.timer(
                        "zhizhida.outbox.publish.duration",
                        "outcome",
                        error == null ? "confirmed" : error));
        return error == null;
    }
}
