package cn.edu.whu.zhizhida.quota;

import cn.edu.whu.zhizhida.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RequestQuotaIntegrationIT extends IntegrationTestBase {
    @Autowired RequestQuotaService quotas;

    @Test
    void concurrentAdmissionNeverExceedsSharedLimitAndIsolatesSubjects() throws Exception {
        String subject = UUID.randomUUID().toString();
        RequestQuotaService.Charge charge = new RequestQuotaService.Charge(
                "integration-concurrency", subject, 3600, 3, 3, 1, "test limit");
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                futures.add(pool.submit((Callable<Boolean>) () -> {
                    start.await();
                    try {
                        quotas.admit(List.of(charge));
                        return true;
                    } catch (QuotaExceededException ex) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> future : futures) if (future.get(15, TimeUnit.SECONDS)) accepted++;
            assertEquals(3, accepted);
        } finally {
            pool.shutdownNow();
        }
        assertThrows(QuotaExceededException.class, () -> quotas.admit(List.of(charge)));
        quotas.admit(List.of(new RequestQuotaService.Charge(
                "integration-concurrency", UUID.randomUUID().toString(), 3600, 3, 3, 1, "test limit")));
    }

    @Test
    void failedMultiBucketAdmissionRollsBackEveryEarlierCharge() {
        String subject = UUID.randomUUID().toString();
        var available = new RequestQuotaService.Charge("rollback-available", subject, 3600, 2, 2, 1, "limit");
        var exhausted = new RequestQuotaService.Charge("rollback-exhausted", subject, 3600, 1, 1, 1, "limit");
        quotas.admit(List.of(exhausted));
        assertThrows(QuotaExceededException.class, () -> quotas.admit(List.of(available, exhausted)));
        assertThrows(QuotaExceededException.class, () -> quotas.admit(List.of(exhausted, available)));
        quotas.admit(List.of(available));
        quotas.admit(List.of(available));
        assertThrows(QuotaExceededException.class, () -> quotas.admit(List.of(available)));
    }

    @Test
    void reversedChargeOrdersDoNotDeadlockAcrossConnections() throws Exception {
        String subject = UUID.randomUUID().toString();
        var a = new RequestQuotaService.Charge("order-a", subject, 3600, 20, 20, 1, "limit");
        var b = new RequestQuotaService.Charge("order-b", subject, 3600, 20, 20, 1, "limit");
        var pool = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                List<RequestQuotaService.Charge> charges = i % 2 == 0 ? List.of(a, b) : List.of(b, a);
                tasks.add(pool.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    quotas.admit(charges);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) task.get(15, TimeUnit.SECONDS);
        } finally { start.countDown(); pool.shutdownNow(); }
    }
}
