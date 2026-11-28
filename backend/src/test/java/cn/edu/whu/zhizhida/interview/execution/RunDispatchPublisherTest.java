package cn.edu.whu.zhizhida.interview.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import cn.edu.whu.zhizhida.messaging.RocketTransport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

class RunDispatchPublisherTest {
    final RunDispatchOutbox outbox = mock(RunDispatchOutbox.class);
    final RocketTransport transport = mock(RocketTransport.class);
    final RunDispatchPublisher publisher = new RunDispatchPublisher(
            outbox, transport, new JsonMapper(), new SimpleMeterRegistry(), 8, 100);
    final RunDispatchOutbox.Delivery delivery = new RunDispatchOutbox.Delivery(
            new RunDispatchMessage(
                    1,
                    UUID.randomUUID().toString(),
                    "AGENT_RUN_READY",
                    UUID.randomUUID().toString(),
                    0,
                    Instant.now()),
            "token",
            1);

    @Test
    void confirmedEventIsPublishedWithOriginalBusinessKey() {
        assertThat(publisher.send(delivery)).isTrue();
        verify(transport)
                .send(
                        eq("run-ready"),
                        eq(delivery.message().eventId()),
                        eq(new JsonMapper().writeValueAsBytes(delivery.message())));
        verify(outbox).published(delivery);
        verify(outbox, never()).retry(any(), anyString());
    }

    @Test
    void failedOrUnconfirmedSendRetainsOutboxForRetry() {
        doThrow(new IllegalStateException("send timed out"))
                .when(transport)
                .send(anyString(), anyString(), any());
        assertThat(publisher.send(delivery)).isFalse();
        verify(outbox).retry(delivery, "PUBLISH_UNAVAILABLE");
        verify(outbox, never()).published(any());
    }

    @Test
    void failedPollDoesNotLeaveStaleBacklogAndNextPollRecovers() {
        var metrics = new SimpleMeterRegistry();
        var monitored = new RunDispatchPublisher(outbox, transport, new JsonMapper(), metrics, 8, 100);
        when(outbox.claim())
                .thenThrow(new IllegalStateException())
                .thenReturn(java.util.Optional.empty());
        monitored.publish();
        assertThat(metrics.get("zhizhida.outbox.pending").gauge().value()).isNaN();
        var lastPoll = metrics.get("zhizhida.outbox.last.successful.poll.timestamp.seconds").gauge();
        assertThat(lastPoll.value()).isNaN();
        when(outbox.pending()).thenReturn(3L);
        when(outbox.oldestPendingSeconds()).thenReturn(12.5);
        monitored.publish();
        assertThat(metrics.get("zhizhida.outbox.pending").gauge().value()).isEqualTo(3);
        assertThat(metrics.get("zhizhida.outbox.oldest.pending.seconds").gauge().value())
                .isEqualTo(12.5);
        assertThat(lastPoll.value()).isPositive();
    }

    RunDispatchPublisher batched(int size, long budget) {
        return new RunDispatchPublisher(
                outbox, transport, new JsonMapper(), new SimpleMeterRegistry(), size, budget);
    }

    @Test
    void batchSizeOnePublishesOnlyOnePerTick() {
        when(outbox.claim()).thenReturn(java.util.Optional.of(delivery));
        batched(1, 100).publish();
        verify(outbox, times(1)).claim();
        verify(outbox, times(1)).published(delivery);
    }

    @Test
    void nextLeaseIsClaimedOnlyAfterPriorConfirmAndDatabaseCommit() {
        var second = new RunDispatchOutbox.Delivery(
                new RunDispatchMessage(
                        1,
                        UUID.randomUUID().toString(),
                        "AGENT_RUN_READY",
                        UUID.randomUUID().toString(),
                        0,
                        Instant.now()),
                "second",
                1);
        when(outbox.claim())
                .thenReturn(java.util.Optional.of(delivery), java.util.Optional.of(second));
        batched(2, 1000).publish();
        var order = inOrder(outbox, transport);
        order.verify(outbox).claim();
        order.verify(transport).send(anyString(), anyString(), any());
        order.verify(outbox).published(delivery);
        order.verify(outbox).claim();
        order.verify(transport).send(anyString(), anyString(), any());
        order.verify(outbox).published(second);
        verify(outbox, times(2)).claim();
    }

    @Test
    void emptyQueueDoesNotKeepPollingWithinBatch() {
        when(outbox.claim()).thenReturn(java.util.Optional.empty());
        batched(8, 1000).publish();
        verify(outbox, times(1)).claim();
        verify(transport, never()).send(anyString(), anyString(), any());
    }

    @Test
    void nackStopsBatchAndRetainsUnclaimedLeases() {
        when(outbox.claim()).thenReturn(java.util.Optional.of(delivery));
        doThrow(new IllegalStateException()).when(transport).send(anyString(), anyString(), any());
        batched(8, 1000).publish();
        verify(outbox, times(1)).claim();
        verify(outbox).retry(delivery, "PUBLISH_UNAVAILABLE");
    }

    @Test
    void databaseFailureAfterConfirmStopsBatchForLeaseRecovery() {
        when(outbox.claim()).thenReturn(java.util.Optional.of(delivery));
        doThrow(new IllegalStateException()).when(outbox).published(delivery);
        batched(8, 1000).publish();
        verify(outbox, times(1)).claim();
        verify(outbox, never()).retry(any(), anyString());
    }

    @Test
    void elapsedBudgetStopsFurtherClaimsWithoutAbandoningCurrentDelivery() {
        when(outbox.claim()).thenReturn(java.util.Optional.of(delivery));
        doAnswer(
                i -> {
                    Thread.sleep(10);
                    return null;
                })
                .when(outbox)
                .published(delivery);
        batched(8, 1).publish();
        verify(outbox, times(1)).claim();
        verify(outbox).published(delivery);
    }

    @Test
    void interruptionPreservesFlagAndLeavesRemainingBatchUnclaimed() {
        when(outbox.claim()).thenReturn(java.util.Optional.of(delivery));
        doAnswer(
                i -> {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("stopped");
                })
                .when(transport)
                .send(anyString(), anyString(), any());
        try {
            batched(8, 1000).publish();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(outbox, times(1)).claim();
            verify(outbox).retry(delivery, "INTERRUPTED");
        } finally {
            Thread.interrupted();
        }
    }
}
