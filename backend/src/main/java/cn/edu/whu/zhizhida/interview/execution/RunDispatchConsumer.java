package cn.edu.whu.zhizhida.interview.execution;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import cn.edu.whu.zhizhida.messaging.RocketTransport;
import cn.edu.whu.zhizhida.messaging.RocketTransport.Delivery;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import tools.jackson.databind.ObjectMapper;

/** 消费就绪和延迟通知，从数据库读取任务当前状态。重复通知仍须通过租约检查才能触发执行。 */
@Component
@ConditionalOnProperty(name = "app.run-dispatch.mode", havingValue = "rocketmq")
public class RunDispatchConsumer {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RunDispatchConsumer.class);
    private final InterviewRunStore store;
    private final InterviewRunWorker worker;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final RunDispatchRetry retry;

    private final RocketTransport transport;
    private final Environment env;

    public RunDispatchConsumer(
            InterviewRunStore store,
            InterviewRunWorker worker,
            ObjectMapper json,
            MeterRegistry metrics,
            RunDispatchRetry retry,
            RocketTransport transport,
            Environment env) {
        this.transport = transport;
        this.env = env;
        this.store = store;
        this.worker = worker;
        this.json = json;
        this.metrics = metrics;
        this.retry = retry;
    }

    @PostConstruct
    void subscribe() {
        if (env.getProperty("interview.runs.worker-enabled", Boolean.class, true))
            transport.listen(
                    "run-workers",
                    List.of("run-ready", "run-delay"),
                    env.getProperty("app.run-dispatch.concurrency", Integer.class, 2),
                    this::receive);
    }

    public boolean receive(Delivery message) {
        if (message.body().length > 4096)
            return invalid(message, "payload-size");
        RunDispatchMessage event;
        try {
            event = json.readValue(message.body(), RunDispatchMessage.class);
        } catch (RuntimeException ex) {
            return invalid(message, "invalid-json");
        }
        try {
            event.validate();
        } catch (RuntimeException ex) {
            return invalid(message, "event-schema");
        }
        if (!List.of(event.eventId()).equals(message.keys()))
            return invalid(message, "business-key");
        String retryReason = null;
        try {
            var candidate = store.dispatchCandidate(event.runId());
            if (candidate.isPresent() && !candidate.get().terminal()) {
                if (!worker.executeCandidate(candidate.get()))
                    retryReason = "waiting";
            }
        } catch (RuntimeException ex) {
            retryReason = "execution";
            log.warn(
                    "event=run.delivery outcome=retry runId={} eventId={} errorType={}",
                    event.runId(),
                    event.eventId(),
                    ex.getClass().getSimpleName());
        }
        if (retryReason != null) {
            // 需要重试时，先等 Broker 确认延迟通知。发送失败则保留原消息，等待重投。
            if (Thread.currentThread().isInterrupted())
                return false;
            try {
                retry.publish(message);
            } catch (RuntimeException ex) {
                metrics.counter("zhizhida.run.delivery", "outcome", "retry_unavailable")
                        .increment();
                return false;
            }
            metrics.counter("zhizhida.run.delivery", "outcome", "retry_" + retryReason).increment();
        } else {
            metrics.counter("zhizhida.run.delivery", "outcome", "ack").increment();
        }
        return true;
    }

    private boolean invalid(Delivery message, String reason) {
        metrics.counter("zhizhida.run.delivery", "outcome", "invalid").increment();
        log.warn("event=run.delivery outcome=invalid reason={}", reason);
        transport.quarantine(message, reason);
        return true;
    }
}
