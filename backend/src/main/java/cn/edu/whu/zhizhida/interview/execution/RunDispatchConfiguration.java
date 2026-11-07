package cn.edu.whu.zhizhida.interview.execution;

import org.springframework.amqp.core.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="app.run-dispatch.mode",havingValue="rabbitmq")
public class RunDispatchConfiguration {
    // 两个投递任务各自串行等待 confirm，共用专属线程池，避免占用恢复与 SSE 的调度线程。
    @Bean(defaultCandidate=false)
    ThreadPoolTaskScheduler publisherScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("mq-publisher-");
        return scheduler;
    }

    @Bean
    @ConditionalOnProperty(name="app.outbox.publisher.enabled",havingValue="true",matchIfMissing=true)
    RunDispatchPublisher publisher(RunDispatchOutbox outbox,org.springframework.amqp.rabbit.core.RabbitTemplate rabbit,
            tools.jackson.databind.ObjectMapper json,io.micrometer.core.instrument.MeterRegistry metrics,
            @org.springframework.beans.factory.annotation.Value("${app.outbox.publisher.batch-size:8}") int batchSize,
            @org.springframework.beans.factory.annotation.Value("${app.outbox.publisher.batch-budget-ms:100}") long batchBudgetMs,
            @org.springframework.beans.factory.annotation.Value("${app.run-dispatch.namespace:zhizhida.agent}") String namespace) {
        return new RunDispatchPublisher(outbox,rabbit,json,metrics,"rabbitmq",batchSize,batchBudgetMs,namespace+".x");
    }
    @Bean
    @ConditionalOnProperty(name="app.run-recovery.enabled",havingValue="true",matchIfMissing=true)
    RunRecoveryScheduler recovery(InterviewRunStore store, io.micrometer.core.instrument.MeterRegistry metrics) {
        return new RunRecoveryScheduler(store, metrics);
    }
    public static final String EXCHANGE="zhizhida.agent.x";
    public static final String QUEUE="zhizhida.agent.run.q";
    public static final String KEY="run.ready";
    @Bean
    Declarables runTopology(@org.springframework.beans.factory.annotation.Value("${app.run-dispatch.namespace:zhizhida.agent}") String namespace) {
        var exchange=new DirectExchange(namespace+".x",true,false);
        var dead=new DirectExchange(namespace+".dlx",true,false);
        var queue=QueueBuilder.durable(namespace+".run.q").deadLetterExchange(dead.getName()).deadLetterRoutingKey("invalid").build();
        var diagnostic=QueueBuilder.durable(namespace+".dead.q").build();
        return new Declarables(exchange,dead,queue,diagnostic,
                BindingBuilder.bind(queue).to(exchange).with(KEY),
                BindingBuilder.bind(diagnostic).to(dead).with("invalid"));
    }
}
