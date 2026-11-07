package cn.edu.whu.zhizhida.quota;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name = "quota.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class QuotaCleanupScheduler {
    private static final Logger log = LoggerFactory.getLogger(QuotaCleanupScheduler.class);
    private final RequestQuotaService quotas;

    public QuotaCleanupScheduler(RequestQuotaService quotas) {
        this.quotas = quotas;
    }

    @Scheduled(initialDelay = 60000, fixedDelay = 3600000)
    public void run() {
        try {
            log.info("Quota cleanup completed; deleted={}", quotas.deleteExpired(500));
        } catch (RuntimeException ex) {
            log.error("Quota cleanup failed; type={}", ex.getClass().getSimpleName());
        }
    }
}
