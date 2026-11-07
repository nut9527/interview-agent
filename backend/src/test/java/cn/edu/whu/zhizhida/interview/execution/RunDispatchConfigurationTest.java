package cn.edu.whu.zhizhida.interview.execution;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.assertj.core.api.Assertions.*;

class RunDispatchConfigurationTest {
    @EnableScheduling
    static class Scheduling {}

    final ApplicationContextRunner context=new ApplicationContextRunner()
            .withUserConfiguration(RunDispatchConfiguration.class,RunSchedulingConfiguration.class,RunDispatchConsumer.class)
            .withBean(cn.edu.whu.zhizhida.interview.api.QuestionCatalog.class,
                    ()->mock(cn.edu.whu.zhizhida.interview.api.QuestionCatalog.class))
            .withBean(cn.edu.whu.zhizhida.learning.api.PracticeMemoryAccess.class,
                    ()->mock(cn.edu.whu.zhizhida.learning.api.PracticeMemoryAccess.class))
            .withBean(cn.edu.whu.zhizhida.interview.InterviewSourceGuard.class,
                    ()->mock(cn.edu.whu.zhizhida.interview.InterviewSourceGuard.class))
            .withBean(RunDispatchOutbox.class,()->mock(RunDispatchOutbox.class))
            .withBean(InterviewRunStore.class,()->mock(InterviewRunStore.class))
            .withBean(InterviewRunWorker.class,()->mock(InterviewRunWorker.class))
            .withBean(RabbitTemplate.class,()->mock(RabbitTemplate.class))
            .withBean(ObjectMapper.class,JsonMapper::new)
            .withBean(MeterRegistry.class,SimpleMeterRegistry::new);
    @Test void databaseModeCreatesNoMqSchedulersOrListener() {
        context.run(c -> assertThat(c).doesNotHaveBean(RunDispatchPublisher.class)
                .doesNotHaveBean(RunRecoveryScheduler.class).doesNotHaveBean(RunDispatchConsumer.class));
    }
    @Test void rabbitModeHasAllReliabilityComponents() {
        context.withPropertyValues("app.run-dispatch.mode=rabbitmq").run(c -> assertThat(c)
                .hasSingleBean(RunDispatchPublisher.class).hasSingleBean(RunRecoveryScheduler.class).hasSingleBean(RunDispatchConsumer.class));
    }
    @Test void publisherAndRecoveryCanRunOnIndependentInstance() {
        context.withPropertyValues("app.run-dispatch.mode=rabbitmq","app.outbox.publisher.enabled=false",
                "app.run-recovery.enabled=false").run(c -> assertThat(c)
                .doesNotHaveBean(RunDispatchPublisher.class).doesNotHaveBean(RunRecoveryScheduler.class));
    }
    @Test void blockedSsePollingDoesNotBlockPublisherOrRecovery() {
        context.withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(Scheduling.class)
                .withPropertyValues("app.run-dispatch.mode=rabbitmq", "app.outbox.publisher.interval-ms=20",
                        "app.run-recovery.interval-ms=20")
                .run(c -> {
                    var sseEntered = new java.util.concurrent.CountDownLatch(1);
                    var releaseSse = new java.util.concurrent.CountDownLatch(1);
                    c.getBean("sseScheduler", org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class)
                            .execute(() -> {
                                sseEntered.countDown();
                                try { releaseSse.await(5, TimeUnit.SECONDS); }
                                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                            });
                    assertThat(sseEntered.await(5, TimeUnit.SECONDS)).isTrue();
                    try {
                        var publisherThread = new CompletableFuture<String>();
                        doAnswer(i -> {
                            publisherThread.complete(Thread.currentThread().getName());
                            return Optional.empty();
                        }).when(c.getBean(RunDispatchOutbox.class)).claim();
                        var recoveryThread = new CompletableFuture<String>();
                        doAnswer(i -> {
                            recoveryThread.complete(Thread.currentThread().getName());
                            return null;
                        }).when(c.getBean(InterviewRunStore.class)).recoverDispatches();
                        assertThat(publisherThread.get(5, TimeUnit.SECONDS)).startsWith("mq-publisher-");
                        assertThat(recoveryThread.get(5, TimeUnit.SECONDS)).startsWith("run-scheduler-");
                    } finally { releaseSse.countDown(); }
                });
    }
    @Test void rejectsUnboundedBatchConfiguration() {
        for(String invalid:java.util.List.of("batch-size=0","batch-size=33","batch-budget-ms=0","batch-budget-ms=1001")) {
            context.withPropertyValues("app.run-dispatch.mode=rabbitmq","app.outbox.publisher."+invalid)
                    .run(c -> {
                        assertThat(c).hasFailed();
                        assertThat(c.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                                .rootCause().hasMessageStartingWith("Outbox batch");
                    });
        }
    }
}
