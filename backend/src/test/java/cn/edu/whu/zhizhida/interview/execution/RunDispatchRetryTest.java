package cn.edu.whu.zhizhida.interview.execution;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class RunDispatchRetryTest {
    final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    final RunDispatchRetry retry = new RunDispatchRetry(rabbit, "test.agent");

    Message message() {
        var properties = new MessageProperties();
        properties.setMessageId("event-id");
        properties.setHeader("x-death", "old queue history");
        return new Message("{\"runId\":\"run-id\",\"dispatchSeq\":3}".getBytes(StandardCharsets.UTF_8), properties);
    }

    void confirmation(boolean ack, boolean returned) {
        doAnswer(call -> {
            Message message = call.getArgument(2);
            CorrelationData correlation = call.getArgument(3);
            if (returned) correlation.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE", "x", "key"));
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    @Test void retryIsPersistentAndPreservesEventIdentityWithoutDeathHistory() {
        confirmation(true, false);
        var original = message();
        retry.publish(original);
        verify(rabbit).send(eq("test.agent.retry.x"), eq(RunDispatchConfiguration.KEY), argThat(message -> {
            assertThat(message.getBody()).isEqualTo(original.getBody());
            assertThat(message.getMessageProperties().getMessageId()).isEqualTo("event-id");
            assertThat(message.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(message.getMessageProperties().getHeaders()).isEmpty();
            return true;
        }), any(CorrelationData.class));
        verify(rabbit).setMandatory(true);
    }

    @Test void nackAndReturnNeverBecomeSuccessfulHandoffs() {
        confirmation(false, false);
        assertThatThrownBy(() -> retry.publish(message())).isInstanceOf(AmqpException.class);
        confirmation(true, true);
        assertThatThrownBy(() -> retry.publish(message())).isInstanceOf(AmqpException.class);
    }

    @Test void unavailableBrokerNeverBecomesSuccessfulHandoff() {
        doThrow(new AmqpException("offline")).when(rabbit)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThatThrownBy(() -> retry.publish(message())).isInstanceOf(AmqpException.class);
    }

    @Test void interruptedConfirmationPreservesShutdownSignal() {
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> retry.publish(message())).isInstanceOf(AmqpException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test void delayedQueueReturnsToOriginalRoutingKey() {
        var topology = new RunDispatchConfiguration().runTopology("test.agent");
        var queues = topology.getDeclarablesByType(Queue.class);
        var delayed = queues.stream().filter(q -> q.getName().equals("test.agent.retry.q")).findFirst().orElseThrow();
        assertThat(delayed.isDurable()).isTrue();
        assertThat(delayed.getArguments()).containsEntry("x-message-ttl", 2000)
                .containsEntry("x-queue-type", "quorum")
                .containsEntry("x-dead-letter-strategy", "at-least-once")
                .containsEntry("x-overflow", "reject-publish")
                .containsEntry("x-max-length", 10000L)
                .containsEntry("x-dead-letter-exchange", "test.agent.x")
                .containsEntry("x-dead-letter-routing-key", RunDispatchConfiguration.KEY);
        assertThat(topology.getDeclarablesByType(Binding.class)).anySatisfy(binding -> {
            assertThat(binding.getDestination()).isEqualTo(delayed.getName());
            assertThat(binding.getExchange()).isEqualTo("test.agent.retry.x");
            assertThat(binding.getRoutingKey()).isEqualTo(RunDispatchConfiguration.KEY);
        });
    }
}
