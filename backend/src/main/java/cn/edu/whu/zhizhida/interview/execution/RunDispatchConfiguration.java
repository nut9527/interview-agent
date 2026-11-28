package cn.edu.whu.zhizhida.interview.execution;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import cn.edu.whu.zhizhida.messaging.RocketTransport;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.run-dispatch.mode", havingValue = "rocketmq")
public class RunDispatchConfiguration {
    // 面试和知识任务分别等待 Broker 回执，共用专属线程池，避免阻塞恢复扫描和 SSE。
    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler publisherScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("mq-publisher-");
        return scheduler;
    }

    @Bean
    @ConditionalOnProperty(name = "app.outbox.publisher.enabled", havingValue = "true", matchIfMissing = true)
    RunDispatchPublisher publisher(
            RunDispatchOutbox outbox,
            RocketTransport transport,
            tools.jackson.databind.ObjectMapper json,
            io.micrometer.core.instrument.MeterRegistry metrics,
            @org.springframework.beans.factory.annotation.Value("${app.outbox.publisher.batch-size:8}") int batchSize,
            @org.springframework.beans.factory.annotation.Value("${app.outbox.publisher.batch-budget-ms:100}") long batchBudgetMs) {
        return new RunDispatchPublisher(outbox, transport, json, metrics, batchSize, batchBudgetMs);
    }

    @Bean
    @ConditionalOnProperty(name = "app.run-recovery.enabled", havingValue = "true", matchIfMissing = true)
    RunRecoveryScheduler recovery(
            InterviewRunStore store, io.micrometer.core.instrument.MeterRegistry metrics) {
        return new RunRecoveryScheduler(store, metrics);
    }

    @Bean
    RunDispatchRetry runDispatchRetry(RocketTransport transport) {
        return new RunDispatchRetry(transport);
    }
}
