package cn.edu.whu.zhizhida.interview.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import cn.edu.whu.zhizhida.messaging.RocketTransport;
import cn.edu.whu.zhizhida.messaging.RocketTransport.Delivery;

class RunDispatchRetryTest {
    final RocketTransport transport = mock(RocketTransport.class);
    final RunDispatchRetry retry = new RunDispatchRetry(transport);
    final Delivery message = new Delivery("run-ready", "broker-id", List.of("event-id"), new byte[] { 2 });

    @Test
    void delayedCopyPreservesBusinessKeyAndPayload() {
        Instant earliest = Instant.now().plusSeconds(2);
        retry.publish(message);
        var time = ArgumentCaptor.forClass(Instant.class);
        verify(transport)
                .sendDelayed(eq("run-delay"), eq("event-id"), same(message.body()), time.capture());
        assertThat(time.getValue()).isBetween(earliest, Instant.now().plusSeconds(2));
    }

    @Test
    void failedDelayReceiptDoesNotAuthorizeOriginalAck() {
        doThrow(new IllegalStateException("unavailable"))
                .when(transport)
                .sendDelayed(anyString(), anyString(), any(), any());
        assertThatThrownBy(() -> retry.publish(message)).isInstanceOf(IllegalStateException.class);
    }
}
