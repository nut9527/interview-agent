package cn.edu.whu.zhizhida.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

class RocketTransportTest {
    private final RocketTransport transport = new RocketTransport(
            new MockEnvironment().withProperty("app.rocketmq.resource-prefix", "isolated"),
            new JsonMapper(),
            new SimpleMeterRegistry());
    private final SimpleConsumer consumer = mock(SimpleConsumer.class);
    private final MessageView message = mock(MessageView.class);

    private void prepare() {
        MessageId id = mock(MessageId.class);
        when(id.toString()).thenReturn("broker-id");
        when(message.getMessageId()).thenReturn(id);
        // 第一个字节位于正文之外，接收端应从缓冲区的当前位置读取。
        when(message.getBody()).thenReturn(ByteBuffer.wrap(new byte[] { 0, 1, 2 }, 1, 2));
        when(message.getKeys()).thenReturn(List.of("business-key"));
        when(message.getTopic()).thenReturn("isolated-run-ready");
    }

    @AfterEach
    void close() {
        transport.close();
    }

    @Test
    void retainsUnconfirmedNotificationAndDoesNotSwallowAckFailure() throws Exception {
        prepare();
        transport.process(
                consumer,
                message,
                "run-workers",
                delivery -> {
                    assertThat(delivery.topic()).isEqualTo(transport.resource("run-ready"));
                    assertThat(delivery.keys()).containsExactly("business-key");
                    assertThat(delivery.messageId()).isEqualTo("broker-id");
                    assertThat(delivery.body()).containsExactly(1, 2);
                    return false;
                });
        verify(consumer, never()).ack(any());
        doThrow(new IllegalStateException("lost receipt")).when(consumer).ack(message);
        assertThatThrownBy(
                () -> transport.process(consumer, message, "run-workers", delivery -> true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void renewsLongTaskBeforeAcknowledging() throws Exception {
        prepare();
        var renewed = new CountDownLatch(1);
        doAnswer(
                call -> {
                    renewed.countDown();
                    return null;
                })
                .when(consumer)
                .changeInvisibleDuration(eq(message), any(Duration.class));
        transport.process(
                consumer,
                message,
                "run-workers",
                delivery -> {
                    try {
                        return renewed.await(12, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                });
        var order = inOrder(consumer);
        order.verify(consumer).changeInvisibleDuration(eq(message), any(Duration.class));
        order.verify(consumer).ack(message);
    }

    @Test
    void sdkWritesDiagnosticsToApplicationConsole() {
        var logger = (org.apache.rocketmq.shaded.ch.qos.logback.classic.Logger) org.apache.rocketmq.shaded.org.slf4j.LoggerFactory
                .getLogger("ROOT");
        assertThat(logger.getAppender("STDERR")).isNotNull();
        assertThat(logger.getAppender("DefaultAppender")).isNull();
    }

    @Test
    @org.junit.jupiter.api.Timeout(7)
    void unresponsiveSdkCannotHoldApplicationShutdown() throws Exception {
        var client = mock(org.apache.rocketmq.client.apis.producer.Producer.class);
        var release = new CountDownLatch(1);
        doAnswer(
                call -> {
                    release.await();
                    return null;
                })
                .when(client)
                .close();
        org.springframework.test.util.ReflectionTestUtils.setField(
                transport,
                "producer",
                java.util.concurrent.CompletableFuture.completedFuture(client));
        try {
            transport.close();
            verify(client).close();
        } finally {
            release.countDown();
        }
    }
}
