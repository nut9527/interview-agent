package cn.edu.whu.zhizhida.interview.execution;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class RunRecoverySchedulerTest {
    @Test void transientDatabaseFailureIsVisibleAndNextSweepRecovers() {
        var store = mock(InterviewRunStore.class);
        var metrics = new SimpleMeterRegistry();
        var scheduler = new RunRecoveryScheduler(store, metrics);
        doThrow(new IllegalStateException()).doNothing().when(store).recoverDispatches();
        scheduler.recover();
        assertThat(metrics.get("zhizhida.run.recovery").tag("outcome", "unavailable").counter().count()).isEqualTo(1);
        assertThat(metrics.get("zhizhida.run.recovery.last.success.seconds").gauge().value()).isNaN();
        scheduler.recover();
        assertThat(metrics.get("zhizhida.run.recovery.last.success.seconds").gauge().value()).isPositive();
        verify(store, times(2)).recoverDispatches();
    }
}
