package cn.edu.whu.zhizhida.interview.execution;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 短周期运行扫描和 SSE 各自串行，避免慢连接、维护任务或 MQ confirm 挤占恢复线程。 */
@Configuration(proxyBeanMethods = false)
public class RunSchedulingConfiguration {
    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler runScheduler() {
        return scheduler("run-scheduler-");
    }

    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler sseScheduler() {
        return scheduler("sse-scheduler-");
    }

    private static ThreadPoolTaskScheduler scheduler(String prefix) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(prefix);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
