package cn.edu.whu.zhizhida.interview.execution;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class RunDispatchPublisherTest {
    final RunDispatchOutbox outbox=mock(RunDispatchOutbox.class);
    final RabbitTemplate rabbit=mock(RabbitTemplate.class);
    final RunDispatchPublisher publisher=new RunDispatchPublisher(outbox,rabbit,new JsonMapper(),new SimpleMeterRegistry(),"rabbitmq");
    final RunDispatchOutbox.Delivery delivery=new RunDispatchOutbox.Delivery(new RunDispatchMessage(1,
            UUID.randomUUID().toString(),"AGENT_RUN_READY",UUID.randomUUID().toString(),0,Instant.now()),"token",1);
    void confirm(boolean ack,boolean returned) {
        doAnswer(invocation->{
            Message message=invocation.getArgument(2);
            assertThat(message.getMessageProperties().getMessageId()).isEqualTo(delivery.message().eventId());
            assertThat(message.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            CorrelationData correlation=invocation.getArgument(3);
            if(returned) correlation.setReturned(new ReturnedMessage(message,312,"NO_ROUTE","x","key"));
            correlation.getFuture().complete(new CorrelationData.Confirm(ack,null));
            return null;
        }).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
    }
    @Test void confirmedAndRoutedEventIsPublished() {
        confirm(true,false); publisher.send(delivery);
        verify(outbox).published(delivery); verify(outbox,never()).retry(any(),anyString());
    }
    @Test void customNamespaceRoutesPublisherToMatchingTopologyExchange() {
        var configured=new RunDispatchConfiguration().publisher(outbox,rabbit,new JsonMapper(),
                new SimpleMeterRegistry(),8,100,"test.isolated");
        confirm(true,false);
        configured.send(delivery);
        verify(rabbit).send(eq("test.isolated.x"),eq(RunDispatchConfiguration.KEY),any(Message.class),any(CorrelationData.class));
        verify(outbox).published(delivery);
    }
    @Test void returnedMessageMustNotBePublishedEvenWithAck() {
        confirm(true,true); publisher.send(delivery);
        verify(outbox).retry(delivery,"RETURNED"); verify(outbox,never()).published(any());
    }
    @Test void nackRetainsOutboxForRetry() {
        confirm(false,false); publisher.send(delivery); verify(outbox).retry(delivery,"NACK");
    }
    @Test void connectionFailureRetainsOutboxForRetry() {
        doThrow(new IllegalStateException()).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        publisher.send(delivery); verify(outbox).retry(delivery,"PUBLISH_UNAVAILABLE");
    }
    @Test void timeoutNeverBecomesSuccess() {
        publisher.send(delivery); verify(outbox).retry(delivery,"CONFIRM_TIMEOUT");
    }
    @Test void failedPollDoesNotLeaveStaleBacklogAndNextPollRecovers() {
        var metrics = new SimpleMeterRegistry();
        var monitored = new RunDispatchPublisher(outbox, rabbit, new JsonMapper(), metrics, "rabbitmq");
        when(outbox.claim()).thenThrow(new IllegalStateException()).thenReturn(java.util.Optional.empty());
        monitored.publish();
        assertThat(metrics.get("zhizhida.outbox.pending").gauge().value()).isNaN();
        var lastPoll = metrics.get("zhizhida.outbox.last.successful.poll.timestamp.seconds").gauge();
        assertThat(lastPoll.value()).isNaN();
        when(outbox.pending()).thenReturn(3L);
        when(outbox.oldestPendingSeconds()).thenReturn(12.5);
        monitored.publish();
        assertThat(metrics.get("zhizhida.outbox.pending").gauge().value()).isEqualTo(3);
        assertThat(metrics.get("zhizhida.outbox.oldest.pending.seconds").gauge().value()).isEqualTo(12.5);
        assertThat(lastPoll.value()).isPositive();
    }
    RunDispatchPublisher batched(int size,long budget) {
        return new RunDispatchPublisher(outbox,rabbit,new JsonMapper(),new SimpleMeterRegistry(),"rabbitmq",size,budget);
    }
    @Test void batchSizeOnePublishesOnlyOnePerTick() {
        when(outbox.claim()).thenReturn(java.util.Optional.of(delivery));
        confirm(true,false);
        batched(1,100).publish();
        verify(outbox,times(1)).claim();
        verify(outbox,times(1)).published(delivery);
    }
    @Test void nextLeaseIsClaimedOnlyAfterPriorConfirmAndDatabaseCommit() {
        var second=new RunDispatchOutbox.Delivery(new RunDispatchMessage(1,UUID.randomUUID().toString(),
                "AGENT_RUN_READY",UUID.randomUUID().toString(),0,Instant.now()),"second",1);
        when(outbox.claim()).thenReturn(java.util.Optional.of(delivery),java.util.Optional.of(second));
        doAnswer(i -> {
            CorrelationData c=i.getArgument(3);
            c.getFuture().complete(new CorrelationData.Confirm(true,null));return null;
        }).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        batched(2,1000).publish();
        var order=inOrder(outbox,rabbit);
        order.verify(outbox).claim();
        order.verify(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        order.verify(outbox).published(delivery);
        order.verify(outbox).claim();
        order.verify(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        order.verify(outbox).published(second);
        verify(outbox,times(2)).claim();
    }
    @Test void emptyQueueDoesNotKeepPollingWithinBatch() {
        when(outbox.claim()).thenReturn(java.util.Optional.empty());
        batched(8,1000).publish();
        verify(outbox,times(1)).claim();
        verify(rabbit,never()).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
    }
    @Test void nackStopsBatchAndRetainsUnclaimedLeases() {
        when(outbox.claim()).thenReturn(java.util.Optional.of(delivery));
        confirm(false,false);
        batched(8,1000).publish();
        verify(outbox,times(1)).claim();
        verify(outbox).retry(delivery,"NACK");
    }
    @Test void databaseFailureAfterConfirmStopsBatchForLeaseRecovery() {
        when(outbox.claim()).thenReturn(java.util.Optional.of(delivery));
        confirm(true,false);
        doThrow(new IllegalStateException()).when(outbox).published(delivery);
        batched(8,1000).publish();
        verify(outbox,times(1)).claim();
        verify(outbox,never()).retry(any(),anyString());
    }
    @Test void elapsedBudgetStopsFurtherClaimsWithoutAbandoningCurrentDelivery() {
        when(outbox.claim()).thenReturn(java.util.Optional.of(delivery));
        confirm(true,false);
        doAnswer(i -> { Thread.sleep(10);return null; }).when(outbox).published(delivery);
        batched(8,1).publish();
        verify(outbox,times(1)).claim();
        verify(outbox).published(delivery);
    }
    @Test void interruptionPreservesFlagAndLeavesRemainingBatchUnclaimed() {
        when(outbox.claim()).thenReturn(java.util.Optional.of(delivery));
        doAnswer(i -> { throw new InterruptedException(); }).when(rabbit)
                .send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        try {
            batched(8,1000).publish();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(outbox,times(1)).claim();
            verify(outbox).retry(delivery,"INTERRUPTED");
        } finally { Thread.interrupted(); }
    }
}
