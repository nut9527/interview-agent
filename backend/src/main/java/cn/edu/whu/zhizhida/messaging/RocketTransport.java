package cn.edu.whu.zhizhida.messaging;

import java.io.Closeable;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.StaticSessionCredentialsProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.ObjectMapper;

/** 按需连接 RocketMQ，使 API 在 Proxy 离线时仍能将任务和 Outbox 记录一起提交。 */
@Component
@ConditionalOnProperty(name = "app.run-dispatch.mode", havingValue = "rocketmq")
public class RocketTransport {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RocketTransport.class);
    private static final Duration INVISIBLE = Duration.ofSeconds(20);
    private final ClientServiceProvider sdk = ClientServiceProvider.loadService();
    private final ClientConfiguration config;
    private final Duration receiveWait;
    private final String prefix;
    private final String probeTopic;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final List<Subscription> subscriptions = new ArrayList<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean stopping;
    private CompletableFuture<Producer> producer;
    private CompletableFuture<Void> probe;
    private volatile boolean available;
    private volatile long lastContact;

    public record Delivery(String topic, String messageId, List<String> keys, byte[] body) {
    }

    public RocketTransport(Environment env, ObjectMapper json, MeterRegistry metrics) {
        this.json = json;
        this.metrics = metrics;
        prefix = env.getProperty("app.rocketmq.resource-prefix", "zhizhida");
        if (!prefix.matches("[a-zA-Z0-9_-]{1,48}"))
            throw new IllegalArgumentException("Invalid RocketMQ resource prefix");
        long receiveWaitMs = env.getProperty("app.rocketmq.receive-wait-ms", Long.class, 200L);
        if (receiveWaitMs < 100 || receiveWaitMs > 30000)
            throw new IllegalArgumentException("RocketMQ receive wait must be 100..30000 ms");
        receiveWait = Duration.ofMillis(receiveWaitMs);
        probeTopic = "worker".equals(env.getProperty("app.runtime.role")) ? "run-delay" : "run-ready";
        var builder = ClientConfiguration.newBuilder()
                .setEndpoints(env.getProperty("app.rocketmq.endpoints", "127.0.0.1:8081"))
                .enableSsl(env.getProperty("app.rocketmq.tls", Boolean.class, false))
                .setMaxStartupAttempts(1)
                .setRequestTimeout(Duration.ofSeconds(6));
        String access = env.getProperty("app.rocketmq.access-key", "");
        String secret = env.getProperty("app.rocketmq.secret-key", "");
        if (access.isBlank() != secret.isBlank())
            throw new IllegalArgumentException(
                    "RocketMQ credentials require both access key and secret key");
        if (!access.isBlank())
            builder.setCredentialProvider(new StaticSessionCredentialsProvider(access, secret));
        config = builder.build();
    }

    public String resource(String suffix) {
        return prefix + "-" + suffix;
    }

    public void send(String topic, String key, byte[] body) {
        sendDelayed(topic, key, body, null);
    }

    public void sendDelayed(String topic, String key, byte[] body, Instant at) {
        try {
            var message = sdk.newMessageBuilder().setTopic(resource(topic)).setKeys(key).setBody(body);
            if (at != null)
                message.setDeliveryTimestamp(at.toEpochMilli());
            // 建连和等待发送回执分别限时，给发布器留出在 Outbox 租约内写回结果的时间。
            var client = producer(topic).get(7, TimeUnit.SECONDS);
            client.sendAsync(message.build()).get(7, TimeUnit.SECONDS);
            contact(true);
        } catch (InterruptedException
                | ExecutionException
                | TimeoutException
                | RuntimeException ex) {
            restoreInterrupt(ex);
            contact(false);
            throw new IllegalStateException("MQ_SEND_FAILED", ex);
        }
    }

    private synchronized CompletableFuture<Producer> producer(String topic) {
        if (stopping || Thread.currentThread().isInterrupted())
            throw new IllegalStateException("MQ_STOPPED");
        if (producer == null || producer.isCompletedExceptionally()) {
            producer = CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            return openProducer(topic);
                        } catch (ClientException ex) {
                            throw new IllegalStateException("MQ_CONNECT_FAILED", ex);
                        }
                    },
                    workers);
        }
        return producer;
    }

    // SDK 可能包装中断异常。沿异常链恢复中断标记，让调用方及时退出。
    private static void restoreInterrupt(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Producer openProducer(String topic) throws ClientException {
        return sdk.newProducerBuilder()
                .setClientConfiguration(config)
                .setTopics(resource(topic))
                .setMaxAttempts(1)
                .build();
    }

    private void contact(boolean success) {
        available = success;
        lastContact = System.nanoTime();
    }

    /** 就绪检查复用最近的收发结果。空闲时异步查询当前角色有权发送的主题路由。 */
    public synchronized boolean reachable() {
        if (stopping)
            return false;
        long age = System.nanoTime() - lastContact;
        if ((lastContact == 0 || age >= TimeUnit.SECONDS.toNanos(5))
                && (probe == null || probe.isDone())) {
            probe = CompletableFuture.runAsync(
                    () -> {
                        try (var client = openProducer(probeTopic)) {
                            contact(true);
                        } catch (Exception ex) {
                            contact(false);
                        }
                    },
                    workers);
        }
        return available && age < TimeUnit.SECONDS.toNanos(12);
    }

    public void quarantine(Delivery message, String reason) {
        // 用原消息 ID 关联诊断记录，正文只留哈希，便于比对异常载荷。
        try {
            var digest = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(message.body()));
            byte[] summary = json.writeValueAsBytes(
                    Map.of(
                            "topic",
                            message.topic(),
                            "messageId",
                            message.messageId(),
                            "keys",
                            message.keys(),
                            "reason",
                            reason,
                            "bodySha256",
                            digest));
            send("dispatch-invalid", message.messageId(), summary);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 每个消费线程一次领取一条通知。处理函数返回 true 后才允许确认原消息。 */
    public synchronized void listen(
            String group, List<String> topics, int concurrency, Predicate<Delivery> handler) {
        if (stopping || concurrency < 1)
            throw new IllegalStateException("Invalid consumer lifecycle or concurrency");
        var subscription = new Subscription(group, topics, handler);
        subscriptions.add(subscription);
        for (int slot = 0; slot < concurrency; slot++)
            workers.submit(subscription::poll);
    }

    private synchronized Subscription subscription(String group) {
        return subscriptions.stream().filter(s -> s.group.equals(group)).findFirst().orElseThrow();
    }

    public void pause(String group) throws InterruptedException {
        subscription(group).pause();
    }

    public void resume(String group) {
        subscription(group).resume();
    }

    public boolean listening(String group) {
        var subscription = subscription(group);
        return subscription.connected && !subscription.paused;
    }

    private final class Subscription {
        private final String group;
        private final Map<String, FilterExpression> topics = new HashMap<>();
        private final Predicate<Delivery> handler;
        private SimpleConsumer consumer;
        private volatile boolean paused;
        private volatile boolean connected;
        private int active;

        Subscription(String group, List<String> suffixes, Predicate<Delivery> handler) {
            this.group = group;
            this.handler = handler;
            for (String suffix : suffixes)
                topics.put(resource(suffix), FilterExpression.SUB_ALL);
        }

        synchronized SimpleConsumer consumer() throws ClientException {
            if (stopping)
                throw new IllegalStateException("MQ_STOPPED");
            if (consumer == null)
                consumer = sdk.newSimpleConsumerBuilder()
                        .setClientConfiguration(config)
                        .setConsumerGroup(resource(group))
                        .setSubscriptionExpressions(topics)
                        .setAwaitDuration(receiveWait)
                        .build();
            return consumer;
        }

        synchronized void pause() throws InterruptedException {
            paused = true;
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (active > 0) {
                long remaining = end - System.nanoTime();
                if (remaining <= 0)
                    throw new IllegalStateException("MQ_PAUSE_TIMEOUT");
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
        }

        synchronized void resume() {
            paused = false;
            notifyAll();
        }

        synchronized boolean beginPoll() throws InterruptedException {
            while (paused && !stopping)
                wait(1000);
            if (stopping)
                return false;
            active++;
            return true;
        }

        void poll() {
            while (!stopping && !Thread.currentThread().isInterrupted()) {
                try {
                    if (!beginPoll())
                        return;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    var client = consumer();
                    connected = true;
                    long start = System.nanoTime();
                    var messages = client.receive(1, INVISIBLE);
                    long elapsed = System.nanoTime() - start;
                    metrics.timer(
                            "zhizhida.mq.receive",
                            "group",
                            group,
                            "outcome",
                            messages.isEmpty() ? "empty" : "received")
                            .record(elapsed, TimeUnit.NANOSECONDS);
                    log.debug(
                            "event=mq.receive group={} count={} elapsedMs={}",
                            group,
                            messages.size(),
                            TimeUnit.NANOSECONDS.toMillis(elapsed));
                    contact(true);
                    for (var message : messages) {
                        if (!stopping)
                            process(client, message, group, handler);
                    }
                } catch (ClientException | RuntimeException ex) {
                    restoreInterrupt(ex);
                    connected = false;
                    contact(false);
                    if (stopping || Thread.currentThread().isInterrupted())
                        return;
                    log.debug(
                            "event=mq.receive group={} errorType={}",
                            group,
                            ex.getClass().getSimpleName());
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                } finally {
                    synchronized (this) {
                        active--;
                        notifyAll();
                    }
                }
            }
        }

        synchronized void close() {
            closeClient(consumer);
        }
    }

    void process(
            SimpleConsumer consumer, MessageView message, String group, Predicate<Delivery> handler)
            throws ClientException {
        var buffer = message.getBody().duplicate();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        var delivery = new Delivery(
                message.getTopic(),
                message.getMessageId().toString(),
                List.copyOf(message.getKeys()),
                bytes);
        var renewal = Thread.ofVirtual()
                .start(
                        () -> {
                            while (!Thread.currentThread().isInterrupted()) {
                                try {
                                    Thread.sleep(INVISIBLE.dividedBy(3));
                                    consumer.changeInvisibleDuration(message, INVISIBLE);
                                } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                } catch (ClientException | RuntimeException ex) {
                                    metrics.counter(
                                            "zhizhida.mq.renewal.failure",
                                            "group",
                                            group)
                                            .increment();
                                    return;
                                }
                            }
                        });
        boolean ack;
        try {
            ack = handler.test(delivery);
        } finally {
            renewal.interrupt();
            try {
                renewal.join(1000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        // 续期和确认使用同一消费凭据。待续期线程退出，再确认原消息。
        if (ack && !renewal.isAlive() && !Thread.currentThread().isInterrupted())
            consumer.ack(message);
    }

    @PreDestroy
    public synchronized void close() {
        if (stopping)
            return;
        stopping = true;
        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS))
                workers.shutdownNow();
        } catch (InterruptedException ex) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
        // Proxy 断连后，SDK 关闭消费者时可能一直等待遥测连接，因此限制停机等待时间。
        var closers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            subscriptions.forEach(subscription -> closers.submit(subscription::close));
            if (producer != null) {
                if (producer.isDone()) {
                    if (!producer.isCompletedExceptionally())
                        closers.submit(() -> closeClient(producer.join()));
                } else
                    producer.thenAcceptAsync(
                            RocketTransport::closeClient, Thread::startVirtualThread);
            }
            closers.shutdown();
            if (!closers.awaitTermination(3, TimeUnit.SECONDS))
                log.warn("event=mq.close outcome=deadline");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            closers.shutdownNow();
        }
    }

    private static void closeClient(Closeable client) {
        if (client == null)
            return;
        try {
            client.close();
        } catch (IOException ex) {
            log.warn("event=mq.close errorType={}", ex.getClass().getSimpleName());
        }
    }
}
