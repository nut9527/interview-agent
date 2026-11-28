package cn.edu.whu.zhizhida.interview.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import cn.edu.whu.zhizhida.messaging.RocketTransport;
import cn.edu.whu.zhizhida.messaging.RocketTransport.Delivery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

class RunDispatchConsumerTest {
    final InterviewRunStore store = mock(InterviewRunStore.class);
    final InterviewRunWorker worker = mock(InterviewRunWorker.class);
    final RocketTransport transport = mock(RocketTransport.class);
    final JsonMapper json = new JsonMapper();
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final RunDispatchRetry retry = mock(RunDispatchRetry.class);
    final RunDispatchConsumer consumer = new RunDispatchConsumer(
            store, worker, json, metrics, retry, transport, new MockEnvironment());
    final String id = UUID.randomUUID().toString();

    Delivery message() {
        var event = new RunDispatchMessage(
                1, UUID.randomUUID().toString(), "AGENT_RUN_READY", id, 0, Instant.now());
        return new Delivery(
                "run-ready", "broker-id", List.of(event.eventId()), json.writeValueAsBytes(event));
    }

    @Test
    void tenDuplicatesOfTerminalRunNeverExecute() throws Exception {
        var row = mock(InterviewRunStore.Row.class);
        when(row.terminal()).thenReturn(true);
        when(store.dispatchCandidate(id)).thenReturn(Optional.of(row));
        for (int i = 0; i < 10; i++)
            assertThat(consumer.receive(message())).isTrue();
        verifyNoInteractions(worker);
    }

    @Test
    void absentRunAndBusyLeaseAreAcknowledgedWithoutExecution() throws Exception {
        when(store.dispatchCandidate(id)).thenReturn(Optional.empty());
        assertThat(consumer.receive(message())).isTrue();
        var row = mock(InterviewRunStore.Row.class);
        when(store.dispatchCandidate(id)).thenReturn(Optional.of(row));
        when(worker.executeCandidate(row)).thenReturn(true);
        assertThat(consumer.receive(message())).isTrue();
        verify(worker).executeCandidate(row);
        verifyNoInteractions(retry);
    }

    @Test
    void ackFollowsSharedWorkerAdmissionAndExecution() throws Exception {
        var row = mock(InterviewRunStore.Row.class);
        when(store.dispatchCandidate(id)).thenReturn(Optional.of(row));
        when(worker.executeCandidate(row)).thenReturn(true);
        assertThat(consumer.receive(message())).isTrue();
        var ordered = inOrder(store, worker);
        ordered.verify(store).dispatchCandidate(id);
        ordered.verify(worker).executeCandidate(row);
    }

    @Test
    void databaseFailureConfirmsDelayedRetryBeforeAck() throws Exception {
        when(store.dispatchCandidate(id)).thenThrow(new IllegalStateException());
        var message = message();
        assertThat(consumer.receive(message)).isTrue();

        verify(retry).publish(message);

        assertThat(
                metrics.get("zhizhida.run.delivery")
                        .tag("outcome", "retry_execution")
                        .counter()
                        .count())
                .isEqualTo(1);
        verifyNoInteractions(worker);
    }

    @Test
    void unpersistedResultGetsBrokerRetry() throws Exception {
        var row = mock(InterviewRunStore.Row.class);
        when(store.dispatchCandidate(id)).thenReturn(Optional.of(row));
        doThrow(new IllegalStateException("RUN_NOT_TERMINAL")).when(worker).executeCandidate(row);
        assertThat(consumer.receive(message())).isTrue();
        verify(retry).publish(any(Delivery.class));
    }

    @Test
    void saturatedWorkerRetriesWithoutClaimOrDatabaseReminder() throws Exception {
        var row = mock(InterviewRunStore.Row.class);
        when(store.dispatchCandidate(id)).thenReturn(Optional.of(row));
        when(worker.executeCandidate(row)).thenReturn(false);
        var message = message();
        assertThat(consumer.receive(message)).isTrue();

        verify(retry).publish(message);

        assertThat(
                metrics.get("zhizhida.run.delivery")
                        .tag("outcome", "retry_waiting")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void unconfirmedRetryKeepsOriginalNotification() throws Exception {
        when(store.dispatchCandidate(id)).thenThrow(new IllegalStateException());
        doThrow(new IllegalStateException("offline")).when(retry).publish(any());
        assertThat(consumer.receive(message())).isFalse();
    }

    @Test
    void shutdownReturnsNotificationAndPreservesInterrupt() throws Exception {
        when(store.dispatchCandidate(id)).thenThrow(new IllegalStateException());
        try {
            Thread.currentThread().interrupt();
            assertThat(consumer.receive(message())).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verifyNoInteractions(retry);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void failedDeliveryDoesNotLogExceptionPayload() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(RunDispatchConsumer.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            when(store.dispatchCandidate(id))
                    .thenThrow(new IllegalStateException("SECRET_PROMPT_AND_CREDENTIAL"));
            assertThat(consumer.receive(message())).isTrue();
            assertThat(logs.list).hasSize(1);
            assertThat(logs.list.getFirst().getFormattedMessage())
                    .contains("outcome=retry", "runId=" + id, "errorType=IllegalStateException")
                    .doesNotContain("SECRET_PROMPT_AND_CREDENTIAL");
            assertThat(logs.list.getFirst().getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void malformedProtocolGoesToDiagnosticTopic() {
        var bad = new Delivery("run-ready", "trace-id", List.of(), new byte[0]);
        assertThat(consumer.receive(bad)).isTrue();
        verify(transport).quarantine(bad, "invalid-json");
        verifyNoInteractions(store, worker);
        doThrow(new IllegalStateException()).when(transport).quarantine(any(), anyString());
        assertThatThrownBy(() -> consumer.receive(bad)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wrongBusinessKeyCannotExecute() {
        var original = message();
        var bad = new Delivery(
                original.topic(),
                original.messageId(),
                List.of("incorrect"),
                original.body());
        assertThat(consumer.receive(bad)).isTrue();
        verify(transport).quarantine(bad, "business-key");
        verifyNoInteractions(worker, store);
    }
}
