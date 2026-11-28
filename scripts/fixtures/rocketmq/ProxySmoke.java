import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;

/** 在隔离的 Compose 网络内使用后端同版 gRPC SDK，验证普通消息和延迟消息都能经 Proxy 收发。 */
public final class ProxySmoke {
    public static void main(String[] args) throws Exception {
        String prefix = System.getenv("ROCKETMQ_RESOURCE_PREFIX");
        if (prefix == null || !prefix.matches("it-[0-9a-f]{16}")) {
            throw new IllegalArgumentException("Expected a runner-owned test prefix");
        }
        String ready = prefix + "-run-ready";
        String delayed = prefix + "-run-delay";
        Map<String, FilterExpression> topics = new HashMap<>();
        topics.put(ready, FilterExpression.SUB_ALL);
        topics.put(delayed, FilterExpression.SUB_ALL);
        ClientServiceProvider sdk = ClientServiceProvider.loadService();
        ClientConfiguration config = ClientConfiguration.newBuilder()
                .setEndpoints("mqproxy:8081").enableSsl(false).setRequestTimeout(Duration.ofSeconds(6)).build();
        String key = UUID.randomUUID().toString();
        try (SimpleConsumer reader = sdk.newSimpleConsumerBuilder().setClientConfiguration(config)
                    .setConsumerGroup(prefix + "-run-workers").setSubscriptionExpressions(topics)
                    .setAwaitDuration(Duration.ofMillis(200)).build();
             Producer producer = sdk.newProducerBuilder().setClientConfiguration(config)
                    .setTopics(ready, delayed).setMaxAttempts(1).build()) {
            reader.receive(1, Duration.ofSeconds(12));
            long due = System.currentTimeMillis() + 2000;
            byte[] payload = key.getBytes(StandardCharsets.UTF_8);
            Map<String, String> receipts = new HashMap<>();
            receipts.put(ready, producer.send(sdk.newMessageBuilder().setTopic(ready)
                    .setKeys(key).setBody(payload).build()).getMessageId().toString());
            receipts.put(delayed, producer.send(sdk.newMessageBuilder().setTopic(delayed)
                    .setKeys(key).setBody(payload).setDeliveryTimestamp(due).build()).getMessageId().toString());
            long timeout = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            while (!receipts.isEmpty() && System.nanoTime() < timeout) {
                for (MessageView message : reader.receive(1, Duration.ofSeconds(12))) {
                    String topic = message.getTopic();
                    if (message.getKeys().contains(key) && message.getMessageId().toString().equals(receipts.get(topic))) {
                        if (delayed.equals(topic) && System.currentTimeMillis() < due - 250) {
                            throw new AssertionError("Delivered before scheduled time");
                        }
                        String actual = StandardCharsets.UTF_8.decode(message.getBody().duplicate()).toString();
                        if (!key.equals(actual)) throw new AssertionError("Body mismatch");
                        reader.ack(message);
                        receipts.remove(topic);
                    } else {
                        throw new AssertionError("Unexpected message in isolated consumer group");
                    }
                }
            }
            if (!receipts.isEmpty()) throw new AssertionError("Missing delivery: " + receipts.keySet());
        }
        System.out.println("Verified normal and delayed delivery and ACK from mqproxy:8081");
    }
}
