package cn.edu.whu.zhizhida.interview.execution;

import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;

/**
 * 消费任务就绪通知，并通过数据库中的任务状态和租约决定是否执行。
 *
 * <p>无容量或执行失败时，经确认将通知移入延迟队列；业务结果以数据库终态为准。
 */
@Component
@ConditionalOnProperty(name="app.run-dispatch.mode",havingValue="rabbitmq")
public class RunDispatchConsumer {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(RunDispatchConsumer.class);
    private final InterviewRunStore store;
    private final InterviewRunWorker worker;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final RunDispatchRetry retry;
    public RunDispatchConsumer(InterviewRunStore store,InterviewRunWorker worker,ObjectMapper json,MeterRegistry metrics,
            RunDispatchRetry retry) {
        this.store=store; this.worker=worker; this.json=json; this.metrics=metrics;
        this.retry=retry;
    }
    @RabbitListener(queues="${app.run-dispatch.namespace:zhizhida.agent}.run.q",ackMode="MANUAL",concurrency="${app.run-dispatch.concurrency:2}",
            autoStartup="${interview.runs.worker-enabled:true}")
    public void receive(Message message,Channel channel) throws IOException {
        long tag=message.getMessageProperties().getDeliveryTag();
        RunDispatchMessage event;
        try {
            if(message.getBody().length>4096) throw new IllegalArgumentException("MESSAGE_SIZE");
            event=json.readValue(message.getBody(),RunDispatchMessage.class);
            event.validate();
            if(!event.eventId().equals(message.getMessageProperties().getMessageId()))
                throw new IllegalArgumentException("MESSAGE_ID");
        } catch(RuntimeException ex) {
            metrics.counter("zhizhida.run.delivery","outcome","invalid").increment();
            log.warn("event=run.delivery outcome=invalid errorType={}",ex.getClass().getSimpleName());
            channel.basicReject(tag,false);
            return;
        }
        String retryReason=null;
        try {
            var candidate=store.dispatchCandidate(event.runId());
            if(candidate.isPresent() && !candidate.get().terminal()) {
                if(!worker.executeCandidate(candidate.get())) retryReason="waiting";
            }
        } catch(RuntimeException ex) {
            retryReason="execution";
            log.warn("event=run.delivery outcome=retry runId={} eventId={} errorType={}",
                    event.runId(),event.eventId(),ex.getClass().getSimpleName());
        }
        if(retryReason!=null) {
            retry(message,channel,tag,retryReason);
            return;
        }
        channel.basicAck(tag,false);
        metrics.counter("zhizhida.run.delivery","outcome","ack").increment();
    }
    private void retry(Message message,Channel channel,long tag,String reason) throws IOException {
        if(Thread.currentThread().isInterrupted()) {
            channel.basicNack(tag,false,true);
            return;
        }
        try {
            retry.publish(message);
        } catch(RuntimeException ex) {
            metrics.counter("zhizhida.run.delivery","outcome","retry_unavailable").increment();
            // 延迟队列自身不可用时限制重入速率；正常重试完全由 Broker 的 TTL 调度。
            try { Thread.sleep(1000); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            channel.basicNack(tag,false,true);
            return;
        }
        channel.basicAck(tag,false);
        metrics.counter("zhizhida.run.delivery","outcome","retry_"+reason).increment();
    }
}
