package cn.edu.whu.zhizhida.ai;

import cn.edu.whu.zhizhida.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiCallGuardTest {
    private final AtomicLong now = new AtomicLong();

    @Test
    void opensAfterThresholdAndRecoversAfterSuccessfulProbe() {
        AiCallGuard guard = guard(2, 2);
        fail(guard);
        fail(guard);
        unavailable(guard);
        now.set(Duration.ofSeconds(30).toNanos());
        assertThat(guard.execute(() -> "probe")).isEqualTo("probe");
        assertThat(guard.execute(() -> "normal")).isEqualTo("normal");
    }

    @Test
    void successfulCallResetsConsecutiveFailureCount() {
        AiCallGuard guard = guard(1, 2);
        fail(guard);
        guard.execute(() -> "ok");
        fail(guard);
        assertThat(guard.execute(() -> "still available")).isEqualTo("still available");
    }

    @Test
    void failedProbeStartsANewCooldown() {
        AiCallGuard guard = guard(1, 1);
        fail(guard);
        now.set(Duration.ofSeconds(30).toNanos());
        fail(guard);
        now.set(Duration.ofSeconds(59).toNanos());
        unavailable(guard);
        now.set(Duration.ofSeconds(60).toNanos());
        assertThat(guard.execute(() -> "recovered")).isEqualTo("recovered");
    }

    @Test
    void rejectsOverloadImmediatelyAndReleasesCapacity() throws Exception {
        AiCallGuard guard = guard(1, 2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(() -> guard.execute(() -> blocked(entered, release)));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            unavailable(guard);
            release.countDown();
            assertThat(pending.get(5, TimeUnit.SECONDS)).isEqualTo("ok");
            assertThat(guard.execute(() -> "released")).isEqualTo("released");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void onlyOneRecoveryProbeIsAdmitted() throws Exception {
        AiCallGuard guard = guard(3, 1);
        fail(guard);
        now.set(Duration.ofSeconds(30).toNanos());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var probe = executor.submit(() -> guard.execute(() -> blocked(entered, release)));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            unavailable(guard);
            unavailable(guard);
            release.countDown();
            probe.get(5, TimeUnit.SECONDS);
            assertThat(guard.execute(() -> "recovered")).isEqualTo("recovered");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void oldInFlightSuccessCannotCloseANewerCircuit() throws Exception {
        AiCallGuard guard = guard(2, 1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var old = executor.submit(() -> guard.execute(() -> blocked(entered, release)));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            fail(guard);
            release.countDown();
            old.get(5, TimeUnit.SECONDS);
            unavailable(guard);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void failureAndCircuitRejectionDoNotLeakCapacity() {
        AiCallGuard guard = guard(1, 1);
        fail(guard);
        for (int i = 0; i < 10; i++) unavailable(guard);
        now.set(Duration.ofSeconds(30).toNanos());
        assertThat(guard.execute(() -> "ok")).isEqualTo("ok");
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> guard(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiCallGuard(1, 1, Duration.ZERO, now::get))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AiCallGuard guard(int capacity, int failures) {
        return new AiCallGuard(capacity, failures, Duration.ofSeconds(30), now::get);
    }

    private void fail(AiCallGuard guard) {
        assertThatThrownBy(() -> guard.execute(() -> { throw new IllegalStateException("upstream failed"); }))
                .isInstanceOf(IllegalStateException.class);
    }

    private void unavailable(AiCallGuard guard) {
        assertThatThrownBy(() -> guard.execute(() -> { throw new AssertionError("must not call provider"); }))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    private String blocked(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("worker timed out");
            return "ok";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
