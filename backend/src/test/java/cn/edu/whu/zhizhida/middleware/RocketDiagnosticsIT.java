package cn.edu.whu.zhizhida.middleware;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.env.MockEnvironment;

import cn.edu.whu.zhizhida.interview.execution.InterviewRunStore;
import cn.edu.whu.zhizhida.interview.execution.InterviewRunWorker;
import cn.edu.whu.zhizhida.interview.execution.RunDispatchConsumer;
import cn.edu.whu.zhizhida.interview.execution.RunDispatchRetry;
import cn.edu.whu.zhizhida.messaging.RocketTransport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "RUN_BROKER_FAULT_TESTS", matches = "true")
class RocketDiagnosticsIT {
    private final JsonMapper json = new JsonMapper();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final MockEnvironment env = new MockEnvironment()
            .withProperty("app.rocketmq.endpoints", TestBroker.endpoints())
            .withProperty("app.rocketmq.resource-prefix", TestBroker.prefix());
    private final RocketTransport transport = new RocketTransport(env, json, metrics);

    @AfterEach
    void close() {
        transport.close();
    }

    private SimpleConsumer open(String group, String topic) throws Exception {
        var consumer = TestBroker.SDK
                .newSimpleConsumerBuilder()
                .setClientConfiguration(TestBroker.config())
                .setConsumerGroup(TestBroker.name(group))
                .setAwaitDuration(Duration.ofSeconds(5))
                .setSubscriptionExpressions(
                        Map.of(TestBroker.name(topic), FilterExpression.SUB_ALL))
                .build();
        // 先拉取一次，让新消费组在测试消息发布前建立起始位点。
        consumer.receive(1, Duration.ofSeconds(12));
        return consumer;
    }

    private MessageView matching(SimpleConsumer reader, String key) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < end) {
            for (var record : reader.receive(1, Duration.ofSeconds(12))) {
                if (record.getKeys().contains(key))
                    return record;
                reader.ack(record);
            }
        }
        throw new AssertionError("Missing message with test key " + key);
    }

    @Test
    void invalidMessageIsQuarantinedAndQueryableByKey() throws Exception {
        var store = mock(InterviewRunStore.class);
        var worker = mock(InterviewRunWorker.class);
        var handler = new RunDispatchConsumer(
                store,
                worker,
                json,
                metrics,
                new RunDispatchRetry(transport),
                transport,
                env);
        String key = UUID.randomUUID().toString();
        try (var input = open("test-validators", "test-invalid-source");
                var diagnostics = open("test-diagnostics", "dispatch-invalid");
                var audit = open("test-auditors", "dispatch-invalid")) {
            transport.send(
                    "test-invalid-source",
                    key,
                    "malformed-test-body".getBytes(StandardCharsets.UTF_8));
            var original = matching(input, key);
            assertThat(handler.receive(TestBroker.delivery(original))).isTrue();
            input.ack(original);
            String id = original.getMessageId().toString();
            var diagnostic = matching(diagnostics, id);
            var body = json.readTree(TestBroker.delivery(diagnostic).body());
            assertThat(body.path("reason").asString()).isEqualTo("invalid-json");
            assertThat(body.path("keys").get(0).asString()).isEqualTo(key);
            assertThat(body.path("topic").asString())
                    .isEqualTo(TestBroker.name("test-invalid-source"));
            assertThat(body.path("bodySha256").asString()).hasSize(64);
            assertThat(body.toString()).doesNotContain("malformed-test-body");
            diagnostics.ack(diagnostic);
            // 两个消费组各自维护位点。诊断组确认消息后，审计组仍应读到同一条记录。
            var copy = matching(audit, id);
            assertThat(copy.getMessageId()).isEqualTo(diagnostic.getMessageId());
            audit.ack(copy);
            assertThat(queryByKey(TestBroker.name("test-invalid-source"), key)).isTrue();
            verifyNoInteractions(store, worker);
        }
    }

    @Test
    void exhaustedRedeliveryGoesToConsumerGroupDeadLetterTopic() throws Exception {
        String key = UUID.randomUUID().toString();
        try (var reader = open("test-dlq", "test-exhaust-source")) {
            transport.send("test-exhaust-source", key, new byte[] { 1 });
            var original = matching(reader, key);
            assertThat(original.getDeliveryAttempt()).isEqualTo(1);
            String deadTopic = "%DLQ%" + TestBroker.name("test-dlq");
            long end = System.nanoTime() + Duration.ofSeconds(85).toNanos();
            while (System.nanoTime() < end) {
                // 每次领取后都不确认。不可见时间到期后再次领取，触发 Proxy 检查消费组的重试上限。
                reader.receive(1, Duration.ofSeconds(10));
                if (queryByKey(deadTopic, key))
                    return;
            }
            fail("No dead letter for business key " + key);
        }
    }

    private boolean queryByKey(String topic, String key) throws Exception {
        String broker = System.getenv("MQ_TEST_CONTAINER");
        assertThat(broker).matches("[a-f0-9]{12,64}");
        String run = TestBroker.prefix().substring(3);
        String labels = docker("inspect", "--format", "{{json .Config.Labels}}", broker);
        assertThat(json.readTree(labels).path("com.docker.compose.project").asString())
                .isEqualTo("zdmq" + run);
        assertThat(json.readTree(labels).path("com.docker.compose.service").asString())
                .isEqualTo("mqbroker");
        String result = docker(
                "exec",
                broker,
                "sh",
                "mqadmin",
                "queryMsgByKey",
                "-n",
                "mqnamesrv:9876",
                "-t",
                topic,
                "-k",
                key);
        return Pattern.compile("(?im)^\\s*[0-9a-f]{16,}\\s+[0-9]+\\s+[0-9]+\\s*$")
                .matcher(result)
                .find();
    }

    private String docker(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("docker"));
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Timed out reading the isolated broker");
        }
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
