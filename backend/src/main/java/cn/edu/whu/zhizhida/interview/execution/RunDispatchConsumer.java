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
 * <p>消息确认表示通知已处理；暂未领取的任务由持久恢复重新调度，业务结果以数据库终态为准。
 */
@Component
@ConditionalOnProperty(name="app.run-dispatch.mode",havingValue="rabbitmq")
public class RunDispatchConsumer {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(RunDispatchConsumer.class);
    private final InterviewRunStore store;
    private final InterviewRunWorker worker;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final boolean recoveryEnabled;
    public RunDispatchConsumer(InterviewRunStore store,InterviewRunWorker worker,ObjectMapper json,MeterRegistry metrics,
            @org.springframework.beans.factory.annotation.Value("${app.run-recovery.enabled:true}") boolean recoveryEnabled) {
        this.store=store; this.worker=worker; this.json=json; this.metrics=metrics;
        this.recoveryEnabled=recoveryEnabled;
    }
    @RabbitListener(queues="${app.run-dispatch.namespace:zhizhida.agent}.run.q",ackMode="MANUAL",concurrency="${app.run-dispatch.concurrency:2}",
            autoStartup="${interview.runs.worker-enabled:true}")
    public void receive(Message message,Channel channel) throws IOException,InterruptedException {
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
        try {
            var candidate=store.dispatchCandidate(event.runId());
            if(candidate.isPresent() && !candidate.get().terminal()) {
                // 消息只负责唤醒；重复投递仍须争抢数据库租约。无容量时不领取，由持久恢复提醒再次尝试。
                worker.executeCandidate(candidate.get());
            }
        } catch(RuntimeException ex) {
            boolean deferred=recoveryEnabled && !Thread.currentThread().isInterrupted();
            String outcome=deferred ? "deferred" : "retry";
            metrics.counter("zhizhida.run.delivery","outcome",outcome).increment();
            log.warn("event=run.delivery outcome={} runId={} eventId={} errorType={}",
                    outcome,event.runId(),event.eventId(),ex.getClass().getSimpleName());
            // 消息只承载提醒；持久 Run 和 outbox 恢复负责在数据库恢复、租约到期后重发。
            if(deferred) channel.basicAck(tag,false);
            else requeue(channel,tag);
            return;
        }
        // 允许确认无须执行或暂未领取的提醒；Run 仍在数据库中，由恢复扫描保证后续调度。
        channel.basicAck(tag,false);
        metrics.counter("zhizhida.run.delivery","outcome","ack").increment();
    }
    private void requeue(Channel channel,long tag) throws IOException {
        // 显式关闭恢复时仍保留消息与有限退避；关闭进程时立即归还未完成通知。
        try { if(!Thread.currentThread().isInterrupted()) Thread.sleep(1000); }
        catch(InterruptedException ex) { Thread.currentThread().interrupt(); }
        finally { channel.basicNack(tag,false,true); }
    }
}
