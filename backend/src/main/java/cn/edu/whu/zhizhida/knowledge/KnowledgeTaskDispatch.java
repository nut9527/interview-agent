package cn.edu.whu.zhizhida.knowledge;

import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 以数据库任务行记录待投递工作，通知可重复发送；消息丢失后仍可扫描恢复。 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="knowledge.dispatch.mode",havingValue="rabbitmq")
public class KnowledgeTaskDispatch {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(KnowledgeTaskDispatch.class);
    private final KnowledgeImportRepository imports;
    private final KnowledgeImportExecutor executor;
    private final KnowledgeIndexRepository indexes;
    private final ObjectProvider<KnowledgeIndexWorker> indexWorker;
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final String namespace;
    private final boolean publisher,indexEnabled,noticeRecovery;
    private volatile double pending=Double.NaN,oldest=Double.NaN;
    private long reconcileAfter;
    public KnowledgeTaskDispatch(KnowledgeImportRepository imports,KnowledgeImportExecutor executor,
            KnowledgeIndexRepository indexes,ObjectProvider<KnowledgeIndexWorker> indexWorker,
            RabbitTemplate rabbit,ObjectMapper json,MeterRegistry metrics,Environment env) {
        this.imports=imports;this.executor=executor;this.indexes=indexes;this.indexWorker=indexWorker;
        this.rabbit=rabbit;this.json=json;this.metrics=metrics;
        namespace=env.getProperty("knowledge.dispatch.namespace","zhizhida.knowledge");
        publisher=env.getProperty("knowledge.dispatch.publisher-enabled",Boolean.class,true);
        // 拆分部署由 API 持续扫描持久通知，Worker 不需要启动本机发布器。
        noticeRecovery=publisher || "worker".equals(env.getProperty("app.runtime.role","all"));
        indexEnabled=env.getProperty("agent.vector.enabled",Boolean.class,false);
        rabbit.setMandatory(true);
        metrics.gauge("knowledge.import.pending",this,v->v.pending);
        metrics.gauge("knowledge.import.oldest.pending.seconds",this,v->v.oldest);
    }
    @Bean Declarables knowledgeTopology() {
        var exchange=new DirectExchange(namespace+".x",true,false);
        var dead=new DirectExchange(namespace+".dlx",true,false);
        var imports=QueueBuilder.durable(namespace+".import.q").deadLetterExchange(dead.getName()).deadLetterRoutingKey("invalid").build();
        var indexes=QueueBuilder.durable(namespace+".index.q").deadLetterExchange(dead.getName()).deadLetterRoutingKey("invalid").build();
        var errors=QueueBuilder.durable(namespace+".dead.q").build();
        return new Declarables(exchange,dead,imports,indexes,errors,
                BindingBuilder.bind(imports).to(exchange).with("import"),BindingBuilder.bind(indexes).to(exchange).with("index"),
                BindingBuilder.bind(errors).to(dead).with("invalid"));
    }
    @Bean static org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory knowledgeListeners(
            org.springframework.amqp.rabbit.connection.ConnectionFactory connection) {
        var factory=new org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connection);factory.setPrefetchCount(1);factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        return factory;
    }
    @org.springframework.context.event.EventListener
    public void archived(KnowledgeRepository.Archived event) {
        if(indexEnabled) indexes.enqueueVersion(event.id(),event.version(),indexWorker.getObject().profile());
    }
    @Scheduled(scheduler="publisherScheduler",fixedDelayString="${knowledge.dispatch.poll-ms:1000}",initialDelayString="${knowledge.dispatch.initial-delay-ms:0}")
    public void publish() {
        if(!publisher) return;
        try {
            pending=imports.pendingCount();oldest=imports.oldestSeconds();
            // 每轮每队列最多发送一条，限制发布确认的等待量，并让导入和索引都获得投递机会。
            for(String id:imports.pending()) if(imports.reserveNotice(id)) {
                send("import",id,Map.of("id",id,"fileRef",imports.reference(id)));break;
            }
            if(indexEnabled) {
                String profile=indexWorker.getObject().profile();
                if(System.nanoTime()>=reconcileAfter) {
                    indexes.reconcile(profile);reconcileAfter=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
                }
                for(long id:indexes.pending(profile,true)) if(indexes.reserveNotice(id)) {
                    send("index",Long.toString(id),Map.of("id",Long.toString(id)));break;
                }
            }
        } catch(RuntimeException ex) {
            pending=Double.NaN;oldest=Double.NaN;metrics.counter("knowledge.dispatch.poll","outcome","unavailable").increment();
            log.warn("event=knowledge.dispatch.poll outcome=unavailable errorType={}",ex.getClass().getSimpleName());
        }
    }
    private void send(String kind,String id,Map<String,String> payload) {
        var sample=io.micrometer.core.instrument.Timer.start(metrics);
        String outcome="confirmed";
        try {
            var props=new MessageProperties();props.setMessageId(kind+":"+id);
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            var confirm=new CorrelationData(java.util.UUID.randomUUID().toString());
            rabbit.send(namespace+".x",kind,new Message(json.writeValueAsBytes(payload),props),confirm);
            var response=confirm.getFuture().get(5,TimeUnit.SECONDS);
            if(confirm.getReturned()!=null) outcome="returned";
            else if(!response.ack()) outcome="nack";
        } catch(InterruptedException ex) { Thread.currentThread().interrupt();outcome="interrupted"; }
        catch(Exception ex) { outcome="unavailable"; }
        finally {
            metrics.counter("knowledge.dispatch.publish","kind",kind,"outcome",outcome).increment();
            sample.stop(metrics.timer("knowledge.dispatch.publish.duration","kind",kind));
            if(!"confirmed".equals(outcome))
                log.warn("event=knowledge.dispatch.publish kind={} taskId={} outcome={}",kind,id,outcome);
        }
        // 发送成功也仅抑制通知 30 秒；任务仍待执行时重新发送，覆盖通知丢失、重启和重试。
    }
    @RabbitListener(id="knowledge-import",queues="${knowledge.dispatch.namespace:zhizhida.knowledge}.import.q",ackMode="MANUAL",containerFactory="knowledgeListeners",
            concurrency="${knowledge.import.concurrency:2}",autoStartup="${knowledge.import.worker-enabled:true}")
    public void importMessage(Message message,Channel channel) throws IOException,InterruptedException { receive("import",message,channel); }
    @RabbitListener(id="knowledge-index",queues="${knowledge.dispatch.namespace:zhizhida.knowledge}.index.q",ackMode="MANUAL",containerFactory="knowledgeListeners",
            concurrency="${knowledge.index.concurrency:1}",autoStartup="#{${agent.vector.enabled:false} && ${agent.vector.worker-enabled:true}}")
    public void indexMessage(Message message,Channel channel) throws IOException,InterruptedException { receive("index",message,channel); }
    private void receive(String kind,Message message,Channel channel) throws IOException,InterruptedException {
        long tag=message.getMessageProperties().getDeliveryTag();
        String id;
        try {
            if(message.getBody().length>1024) throw new IllegalArgumentException();
            var body=json.readTree(message.getBody());id=body.path("id").asText();
            if(!message.getMessageProperties().getMessageId().equals(kind+":"+id)) throw new IllegalArgumentException();
            if(kind.equals("import")) {
                if(!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                        || !body.path("fileRef").asText().equals(id+"/source.upload")) throw new IllegalArgumentException();
            } else if(Long.parseLong(id)<=0) throw new IllegalArgumentException();
        } catch(RuntimeException ex) {
            metrics.counter("knowledge.dispatch.delivery","kind",kind,"outcome","invalid").increment();
            log.warn("event=knowledge.dispatch.delivery kind={} outcome=invalid errorType={}",kind,ex.getClass().getSimpleName());
            channel.basicReject(tag,false);return;
        }
        try {
            if(kind.equals("import")) executor.execute(id);
            else indexWorker.getObject().execute(Long.parseLong(id));
        } catch(InterruptedException ex) {
            try { channel.basicNack(tag,false,true); }
            finally { Thread.currentThread().interrupt(); }
            return;
        } catch(RuntimeException ex) {
            boolean deferred=noticeRecovery && !Thread.currentThread().isInterrupted();
            String outcome=deferred ? "deferred" : "retry";
            metrics.counter("knowledge.dispatch.delivery","kind",kind,"outcome",outcome).increment();
            log.warn("event=knowledge.dispatch.delivery kind={} taskId={} outcome={} errorType={}",
                    kind,id,outcome,ex.getClass().getSimpleName());
            // QUEUED 通知和失效 RUNNING 租约均由数据库恢复；避免同一故障通知堵住监听线程。
            if(deferred) channel.basicAck(tag,false);
            else requeue(channel,tag);
            return;
        }
        // ACK 只确认已处理通知；未领取或需重试的任务仍由数据库扫描再次投递。
        channel.basicAck(tag,false);
        metrics.counter("knowledge.dispatch.delivery","kind",kind,"outcome","ack").increment();
    }
    private void requeue(Channel channel,long tag) throws IOException {
        try { if(!Thread.currentThread().isInterrupted()) Thread.sleep(1000); }
        catch(InterruptedException ex) { Thread.currentThread().interrupt(); }
        finally { channel.basicNack(tag,false,true); }
    }
}
