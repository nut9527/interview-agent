package cn.edu.whu.zhizhida.middleware;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.springframework.test.context.DynamicPropertyRegistry;

import cn.edu.whu.zhizhida.messaging.RocketTransport.Delivery;

final class TestBroker {
    static final ClientServiceProvider SDK = ClientServiceProvider.loadService();

    static String endpoints() {
        return System.getenv().getOrDefault("ROCKETMQ_ENDPOINTS", "127.0.0.1:8081");
    }

    /** 运行器为每轮验收分配独立前缀，用它隔离 Topic 和消费组。 */
    static String prefix() {
        String value = System.getenv("ROCKETMQ_RESOURCE_PREFIX");
        if (value == null || !value.matches("it-[0-9a-f]{16}"))
            throw new IllegalStateException("Real MQ tests require a runner-owned resource prefix");
        return value;
    }

    static String name(String suffix) {
        return prefix() + "-" + suffix;
    }

    static void properties(DynamicPropertyRegistry p) {
        p.add("app.rocketmq.endpoints", TestBroker::endpoints);
        p.add("app.rocketmq.resource-prefix", TestBroker::prefix);
    }

    static ClientConfiguration config() {
        return ClientConfiguration.newBuilder()
                .setEndpoints(endpoints())
                .enableSsl(false)
                .setRequestTimeout(Duration.ofSeconds(4))
                .setMaxStartupAttempts(1)
                .build();
    }

    static SimpleConsumer open() throws ClientException {
        return SDK.newSimpleConsumerBuilder()
                .setClientConfiguration(config())
                .setConsumerGroup(name("run-workers"))
                .setAwaitDuration(Duration.ofSeconds(5))
                .setSubscriptionExpressions(
                        Map.of(
                                name("run-ready"),
                                FilterExpression.SUB_ALL,
                                name("run-delay"),
                                FilterExpression.SUB_ALL))
                .build();
    }

    static MessageView receive(SimpleConsumer client, String topic) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(55).toNanos();
        while (System.nanoTime() < until) {
            var messages = client.receive(1, Duration.ofSeconds(12));
            if (messages.isEmpty())
                continue;
            var message = messages.getFirst();
            org.assertj.core.api.Assertions.assertThat(message.getTopic()).isEqualTo(name(topic));
            return message;
        }
        throw new AssertionError("Timed out receiving from " + name(topic));
    }

    static Delivery delivery(MessageView message) {
        var body = message.getBody().duplicate();
        byte[] bytes = new byte[body.remaining()];
        body.get(bytes);
        return new Delivery(
                message.getTopic(),
                message.getMessageId().toString(),
                List.copyOf(message.getKeys()),
                bytes);
    }

    private TestBroker() {
    }
}
