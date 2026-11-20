package cn.edu.whu.zhizhida.interview.execution;

import java.util.concurrent.TimeUnit;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

public class RunDispatchPublisher {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RunDispatchPublisher.class);
    private final String exchange;
    private final RunDispatchOutbox outbox;
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final int batchSize;
    private final long batchBudgetNanos;
    private volatile double pending = Double.NaN;
    private volatile double oldestPending = Double.NaN;
    private volatile double lastSuccessfulPoll = Double.NaN;

    public RunDispatchPublisher(RunDispatchOutbox outbox, RabbitTemplate rabbit, ObjectMapper json,
            MeterRegistry metrics,
            @org.springframework.beans.factory.annotation.Value("${app.run-dispatch.mode:database}") String mode) {
        this(outbox, rabbit, json, metrics, mode, 8, 100);
    }

    public RunDispatchPublisher(RunDispatchOutbox outbox, RabbitTemplate rabbit, ObjectMapper json,
            MeterRegistry metrics,
            String mode, int batchSize, long batchBudgetMs) {
        this(outbox, rabbit, json, metrics, mode, batchSize, batchBudgetMs, RunDispatchConfiguration.EXCHANGE);
    }

    public RunDispatchPublisher(RunDispatchOutbox outbox, RabbitTemplate rabbit, ObjectMapper json,
            MeterRegistry metrics,
            String mode, int batchSize, long batchBudgetMs, String exchange) {
        if (!"rabbitmq".equals(mode))
            throw new IllegalArgumentException("Outbox publisher requires rabbitmq mode");
        if (batchSize < 1 || batchSize > 32)
            throw new IllegalArgumentException("Outbox batch size must be 1..32");
        if (batchBudgetMs < 1 || batchBudgetMs > 1000)
            throw new IllegalArgumentException("Outbox batch budget must be 1..1000ms");
        this.outbox = outbox;
        this.rabbit = rabbit;
        this.json = json;
        this.metrics = metrics;
        this.exchange = exchange;
        this.batchSize = batchSize;
        this.batchBudgetNanos = TimeUnit.MILLISECONDS.toNanos(batchBudgetMs);
        rabbit.setMandatory(true);
        metrics.gauge("zhizhida.outbox.pending", this, value -> value.pending);
        metrics.gauge("zhizhida.outbox.oldest.pending.seconds", this, value -> value.oldestPending);
        metrics.gauge("zhizhida.outbox.last.successful.poll.timestamp.seconds", this,
                value -> value.lastSuccessfulPoll);
    }

    @Scheduled(scheduler = "publisherScheduler", fixedDelayString = "${app.outbox.publisher.interval-ms:500}", initialDelayString = "${app.outbox.publisher.initial-delay-ms:0}")
    public void publish() {
        // 上一条消息确认并更新数据库后才领取下一条，避免批量预占的租约在等待确认期间过期。
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
            log.warn("event=run.outbox.poll outcome=unavailable errorType={}", ex.getClass().getSimpleName());
        }
    }

    boolean send(RunDispatchOutbox.Delivery delivery) {
        var sample = io.micrometer.core.instrument.Timer.start(metrics);
        String error = null;
        try {
            var properties = new MessageProperties();
            properties.setMessageId(delivery.message().eventId());
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            var correlation = new CorrelationData(delivery.message().eventId() + ":" + delivery.token());
            rabbit.send(exchange, RunDispatchConfiguration.KEY,
                    new Message(json.writeValueAsBytes(delivery.message()), properties), correlation);
            var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            // 框架在完成 confirm future 之前填入关联 Return；ACK 本身不代表可路由。
            if (correlation.getReturned() != null)
                error = "RETURNED";
            else if (!confirm.ack())
                error = "NACK";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            error = "INTERRUPTED";
        } catch (java.util.concurrent.TimeoutException ex) {
            error = "CONFIRM_TIMEOUT";
        } catch (Exception ex) {
            error = "PUBLISH_UNAVAILABLE";
        }
        // 确认后落库失败仍可能触发重投；消息只是唤醒提示，执行资格由数据库租约裁决。
        if (error == null)
            outbox.published(delivery);
        else {
            log.warn("event=run.outbox.publish outcome={} runId={} eventId={} attempt={}",
                    error, delivery.message().runId(), delivery.message().eventId(), delivery.attempts());
            outbox.retry(delivery, error);
        }
        metrics.counter("zhizhida.outbox.publish", "outcome", error == null ? "confirmed" : error).increment();
        sample.stop(metrics.timer("zhizhida.outbox.publish.duration", "outcome", error == null ? "confirmed" : error));
        return error == null;
    }
}
