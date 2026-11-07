package cn.edu.whu.zhizhida.interview.execution;

import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class RunDispatchConsumerTest {
    final InterviewRunStore store=mock(InterviewRunStore.class);
    final InterviewRunWorker worker=mock(InterviewRunWorker.class);
    final Channel channel=mock(Channel.class);
    final JsonMapper json=new JsonMapper();
    final SimpleMeterRegistry metrics=new SimpleMeterRegistry();
    final RunDispatchConsumer consumer=new RunDispatchConsumer(store,worker,json,metrics,true);
    final String id=UUID.randomUUID().toString();
    Message message() {
        var event=new RunDispatchMessage(1,UUID.randomUUID().toString(),"AGENT_RUN_READY",id,0,Instant.now());
        var props=new MessageProperties(); props.setMessageId(event.eventId()); props.setDeliveryTag(7);
        return new Message(json.writeValueAsBytes(event),props);
    }
    @Test void tenDuplicatesOfTerminalRunNeverExecute() throws Exception {
        var row=mock(InterviewRunStore.Row.class); when(row.terminal()).thenReturn(true);
        when(store.dispatchCandidate(id)).thenReturn(Optional.of(row));
        for(int i=0;i<10;i++) consumer.receive(message(),channel);
        verify(channel,times(10)).basicAck(7,false); verifyNoInteractions(worker);
    }
    @Test void absentRunAndBusyLeaseAreAcknowledgedWithoutExecution() throws Exception {
        when(store.dispatchCandidate(id)).thenReturn(Optional.empty());
        consumer.receive(message(),channel);
        var row=mock(InterviewRunStore.Row.class); when(store.dispatchCandidate(id)).thenReturn(Optional.of(row));
        consumer.receive(message(),channel);
        verify(channel,times(2)).basicAck(7,false); verify(worker).executeCandidate(row);
    }
    @Test void ackFollowsSharedWorkerAdmissionAndExecution() throws Exception {
        var row=mock(InterviewRunStore.Row.class);
        when(store.dispatchCandidate(id)).thenReturn(Optional.of(row));
        consumer.receive(message(),channel);
        var ordered=inOrder(store,worker,channel);
        ordered.verify(store).dispatchCandidate(id);
        ordered.verify(worker).executeCandidate(row); ordered.verify(channel).basicAck(7,false);
    }
    @Test void databaseFailureDefersToDurableRecoveryWithoutBrokerRequeue() throws Exception {
        when(store.dispatchCandidate(id)).thenThrow(new IllegalStateException());
        consumer.receive(message(),channel);
        verify(channel).basicAck(7,false); verify(channel,never()).basicNack(anyLong(),anyBoolean(),anyBoolean());
        assertThat(metrics.get("zhizhida.run.delivery").tag("outcome","deferred").counter().count()).isEqualTo(1);
        verifyNoInteractions(worker);
    }
    @Test void unpersistedResultDefersToLeaseRecovery() throws Exception {
        var row=mock(InterviewRunStore.Row.class); when(store.dispatchCandidate(id)).thenReturn(Optional.of(row));
        doThrow(new IllegalStateException("RUN_NOT_TERMINAL")).when(worker).executeCandidate(row);
        consumer.receive(message(),channel); verify(channel).basicAck(7,false);
        verify(channel,never()).basicNack(anyLong(),anyBoolean(),anyBoolean());
    }
    @Test void disabledRecoveryKeepsNotificationForBrokerRetry() throws Exception {
        when(store.dispatchCandidate(id)).thenThrow(new IllegalStateException());
        new RunDispatchConsumer(store,worker,json,metrics,false).receive(message(),channel);
        verify(channel).basicNack(7,false,true); verify(channel,never()).basicAck(anyLong(),anyBoolean());
    }
    @Test void shutdownReturnsNotificationAndPreservesInterrupt() throws Exception {
        when(store.dispatchCandidate(id)).thenThrow(new IllegalStateException());
        try {
            Thread.currentThread().interrupt();
            consumer.receive(message(),channel);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(channel).basicNack(7,false,true); verify(channel,never()).basicAck(anyLong(),anyBoolean());
        } finally { Thread.interrupted(); }
    }
    @Test void failedDeferredAckPropagatesForBrokerRecovery() throws Exception {
        when(store.dispatchCandidate(id)).thenThrow(new IllegalStateException());
        doThrow(new java.io.IOException("connection lost")).when(channel).basicAck(7,false);
        assertThatThrownBy(()->consumer.receive(message(),channel)).isInstanceOf(java.io.IOException.class);
        verify(channel,never()).basicNack(anyLong(),anyBoolean(),anyBoolean());
    }
    @Test void failedDeferredDeliveryDoesNotLogExceptionPayload() throws Exception {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(RunDispatchConsumer.class);
        var logs=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start();logger.addAppender(logs);
        try {
            when(store.dispatchCandidate(id)).thenThrow(new IllegalStateException("SECRET_PROMPT_AND_CREDENTIAL"));
            consumer.receive(message(),channel);
            assertThat(logs.list).hasSize(1);
            assertThat(logs.list.getFirst().getFormattedMessage()).contains("outcome=deferred","runId="+id,
                    "errorType=IllegalStateException").doesNotContain("SECRET_PROMPT_AND_CREDENTIAL");
            assertThat(logs.list.getFirst().getThrowableProxy()).isNull();
        } finally { logger.detachAppender(logs);logs.stop(); }
    }
    @Test void malformedProtocolGoesToDiagnosticQueue() throws Exception {
        var props=new MessageProperties(); props.setDeliveryTag(7);
        consumer.receive(new Message("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),props),channel);
        verify(channel).basicReject(7,false); verifyNoInteractions(store,worker);
    }
}
