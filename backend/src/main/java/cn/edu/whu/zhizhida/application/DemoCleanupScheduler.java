package cn.edu.whu.zhizhida.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name = "demo.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class DemoCleanupScheduler {
    private static final Logger log = LoggerFactory.getLogger(DemoCleanupScheduler.class);
    private final DemoCleanupWorker worker;

    public DemoCleanupScheduler(DemoCleanupWorker worker) {
        this.worker = worker;
    }

    @Scheduled(initialDelayString = "${demo.cleanup.interval-ms:60000}",
            fixedDelayString = "${demo.cleanup.interval-ms:60000}")
    public void run() {
        long started = System.nanoTime();
        try {
            int deleted = worker.runBatch();
            long remaining = worker.remaining();
            log.info("Demo cleanup completed; deleted={} remaining={} durationMs={}",
                    deleted, remaining, (System.nanoTime() - started) / 1_000_000);
        } catch (RuntimeException ex) {
            log.error("Demo cleanup failed; type={} durationMs={}", ex.getClass().getSimpleName(),
                    (System.nanoTime() - started) / 1_000_000);
        }
    }
}
