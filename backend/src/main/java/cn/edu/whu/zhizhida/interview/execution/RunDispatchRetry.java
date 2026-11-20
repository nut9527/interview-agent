package cn.edu.whu.zhizhida.interview.execution;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** 重试消息在延迟队列持久化并确认后，消费端才可确认原消息。 */
public class RunDispatchRetry {
    private final RabbitTemplate rabbit;
    private final String exchange;

    public RunDispatchRetry(RabbitTemplate rabbit, String namespace) {
        this.rabbit = rabbit;
        this.exchange = namespace + ".retry.x";
        rabbit.setMandatory(true);
    }

    public void publish(Message original) {
        var properties = new MessageProperties();
        properties.setMessageId(original.getMessageProperties().getMessageId());
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        var confirmation = new CorrelationData(UUID.randomUUID().toString());
        rabbit.send(exchange, RunDispatchConfiguration.KEY,
                new Message(original.getBody(), properties), confirmation);
        try {
            var result = confirmation.getFuture().get(5, TimeUnit.SECONDS);
            if (!result.ack() || confirmation.getReturned() != null)
                throw new AmqpException("Run retry was not routed and confirmed");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AmqpException("Run retry interrupted", ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new AmqpException("Run retry confirmation failed", ex);
        }
    }
}
