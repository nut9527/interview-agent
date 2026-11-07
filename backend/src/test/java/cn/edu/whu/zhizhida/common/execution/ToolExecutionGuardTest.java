package cn.edu.whu.zhizhida.common.execution;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ToolExecutionGuardTest {
    @Test void ignoredInterruptDoesNotFreeCapacityOrDeliverLateSuccess() throws Exception {
        try (var guard = new ToolExecutionGuard(1)) {
            var release = new CountDownLatch(1);
            var callbackThread = new AtomicReference<Thread>();
            try {
                assertThatThrownBy(() -> guard.call(300, () -> {
                    callbackThread.set(Thread.currentThread());
                    boolean waiting = true;
                    while (waiting) {
                        try { release.await(); waiting = false; }
                        catch (InterruptedException ignored) { }
                    }
                    return "late private result";
                })).hasMessage("TOOL_TIMEOUT");
                assertThat(callbackThread.get()).isNotNull();
                assertThatThrownBy(() -> guard.call(1000, () -> "must not execute")).hasMessage("TOOL_CAPACITY");
            } finally {
                release.countDown();
                if (callbackThread.get() != null) callbackThread.get().join(2000);
            }
            assertThat(guard.call(1000, () -> "next task")).isEqualTo("next task");
        }
    }

    @Test void callerCancellationInterruptsCallbackAndPreservesCallerInterrupt() throws Exception {
        try (var guard = new ToolExecutionGuard(1)) {
            var entered = new CountDownLatch(1);
            var interrupted = new CountDownLatch(1);
            var preserved = new AtomicBoolean();
            var failure = new AtomicReference<Throwable>();
            var caller = Thread.ofVirtual().start(() -> {
                try {
                    guard.call(10_000, () -> {
                        entered.countDown();
                        try { new CountDownLatch(1).await(); }
                        catch (InterruptedException ex) { interrupted.countDown(); throw ex; }
                        return "unreachable";
                    });
                } catch (Throwable ex) { failure.set(ex); preserved.set(Thread.currentThread().isInterrupted()); }
            });
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                caller.interrupt(); caller.join(2000);
                assertThat(failure.get()).hasMessage("TOOL_INTERRUPTED");
                assertThat(preserved).isTrue();
                assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
            } finally { caller.interrupt(); caller.join(2000); }
        }
    }

    @Test void rejectedAdmissionAndClosedGuardNeverInvokeCallback() {
        var invoked = new AtomicBoolean();
        try (var guard = new ToolExecutionGuard(1)) {
            assertThatThrownBy(() -> guard.call(0, () -> invoked.getAndSet(true))).hasMessage("RUN_TOOL_BUDGET");
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> guard.call(1000, () -> invoked.getAndSet(true))).hasMessage("TOOL_INTERRUPTED");
            } finally { Thread.interrupted(); }
            guard.close();
            assertThatThrownBy(() -> guard.call(1000, () -> invoked.getAndSet(true))).hasMessage("TOOL_UNAVAILABLE");
            assertThat(invoked).isFalse();
        }
        assertThatThrownBy(() -> new ToolExecutionGuard(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void failurePreservesClassificationAndFreesCapacityWithoutLeakingCheckedMessages() {
        try (var guard = new ToolExecutionGuard(1)) {
            assertThatThrownBy(() -> guard.call(1000, () -> { throw new RunFailureException("TOOL_DENIED"); }))
                    .hasMessage("TOOL_DENIED");
            assertThatThrownBy(() -> guard.call(1000, () -> { throw new java.io.IOException("private body"); }))
                    .hasMessage("TOOL_FAILED").hasNoCause();
            assertThat(guard.call(1000, () -> 7)).isEqualTo(7);
        }
    }
}
