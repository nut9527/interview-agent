package cn.edu.whu.zhizhida.interview.execution;

import static org.assertj.core.api.Assertions.*;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class RunExecutionSlotsTest {
    @Test void invalidCapacityAndDuplicateOwnerAreRejected() {
        assertThatThrownBy(() -> new RunExecutionSlots(0)).isInstanceOf(IllegalArgumentException.class);
        var slots = new RunExecutionSlots(2);
        var first = slots.tryAcquire("a", 1);
        assertThat(slots.tryAcquire("a", 2)).isNull();
        assertThat(slots.tryAcquire("b", 1)).isNull();
        var second = slots.tryAcquire("b", 2);
        assertThat(slots.tryAcquire("c", 3)).isNull();
        first.close(); first.close();
        try (var third = slots.tryAcquire("c", 3)) { assertThat(third).isNotNull(); }
        second.close();
    }

    @Test void concurrentAcquisitionNeverOversubscribesAndOldPermitCannotReleaseNewOne() throws Exception {
        var slots = new RunExecutionSlots(4);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<RunExecutionSlots.Permit>>();
            for (int i = 0; i < 40; i++) {
                int id = i;
                futures.add(executor.submit(() -> { start.await(); return slots.tryAcquire("r" + id, id); }));
            }
            start.countDown();
            var acquired = new ArrayList<RunExecutionSlots.Permit>();
            for (var f : futures) { var p = f.get(5, TimeUnit.SECONDS); if (p != null) acquired.add(p); }
            assertThat(acquired).hasSize(4);
            acquired.forEach(RunExecutionSlots.Permit::close);
        }
        var old = slots.tryAcquire("same", 1); old.close();
        try (var current = slots.tryAcquire("same", 1)) {
            old.close();
            assertThat(slots.tryAcquire("same", 1)).isNull();
        }
    }
}
