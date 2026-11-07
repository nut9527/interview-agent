package cn.edu.whu.zhizhida.interview.execution;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;

import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.interview.api.InterviewRun;

/**
 * Redis 限制跨实例 SSE 连接数；事件轮询与网络写入独立于模型 Worker。
 */
@Service
public class InterviewRunEvents {
    private static final class Connection {
        final long owner;
        final String id;
        final SseEmitter emitter;
        final String permit;
        final long expiresAt;
        final ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(64);
        volatile long seq;
        volatile boolean stopping;
        volatile Thread writer;
        volatile long heartbeat = System.nanoTime();

        Connection(long owner, String id, long seq, SseEmitter emitter, String permit, long expiresAt) {
            this.owner = owner;
            this.id = id;
            this.seq = seq;
            this.emitter = emitter;
            this.permit = permit;
            this.expiresAt = expiresAt;
        }
    }

    private final InterviewRunStore store;
    private final SseConnectionBudget budget;
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final Object lifecycle = new Object();
    private final ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor();
    private boolean closed;

    public InterviewRunEvents(InterviewRunStore store, SseConnectionBudget budget) {
        this.store = store;
        this.budget = budget;
    }

    /** 校验运行归属后，从持久事件游标之后订阅；连接数超限时拒绝，不为订阅触发执行。 */
    public SseEmitter subscribe(long owner, String id, long seq) {
        store.get(owner, id);
        // 从申请前计时，Redis 往返延迟不会延长本地连接有效期。
        long expiresAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SseConnectionBudget.CONNECTION_MILLIS);
        String permit = budget.acquire(owner);
        var emitter = createEmitter();
        var c = new Connection(owner, id, seq, emitter, permit, expiresAt);
        emitter.onCompletion(() -> release(c));
        emitter.onTimeout(() -> release(c));
        emitter.onError(e -> release(c));
        admit(c);
        try {
            writers.execute(() -> write(c));
        } catch (RejectedExecutionException ex) {
            // 关闭可能发生在登记与派发之间，未派发的连接同样必须释放。
            finish(c);
            throw unavailable();
        }
        return emitter;
    }

    SseEmitter createEmitter() { return new SseEmitter(SseConnectionBudget.CONNECTION_MILLIS); }

    private void admit(Connection connection) {
        synchronized (lifecycle) {
            if (closed) {
                budget.release(connection.owner, connection.permit);
                throw unavailable();
            }
            connections.add(connection);
        }
    }

    private void release(Connection connection) {
        stop(connection);
        boolean removed;
        synchronized (lifecycle) {
            // 完成、错误、超时和写线程退出可交错发生，重复释放不影响其他连接。
            removed = connections.remove(connection);
        }
        if (removed) budget.release(connection.owner, connection.permit);
    }

    private void stop(Connection connection) {
        connection.stopping = true;
        // complete() 与 send() 共用 Spring 的写锁，调度线程不能等待慢连接完成网络写入。
        var writer = connection.writer;
        if (writer != null && writer != Thread.currentThread()) writer.interrupt();
    }

    private void finish(Connection connection) {
        try { connection.emitter.complete(); }
        finally { release(connection); }
    }

    private void write(Connection connection) {
        connection.writer = Thread.currentThread();
        try {
            while (!connection.stopping && connections.contains(connection) && System.nanoTime() < connection.expiresAt) {
                Object item = connection.queue.poll(1, TimeUnit.SECONDS);
                if (item == null)
                    continue;
                if (item.equals("close"))
                    break;
                // 缓冲事件也要重新检查归属和存在性，防止删除后继续投递。
                store.get(connection.owner, connection.id);
                SseEmitter.SseEventBuilder output;
                if (item instanceof InterviewRun.Event event)
                    output = SseEmitter.event().id(Long.toString(event.seq())).name(event.type()).data(event);
                else
                    output = SseEmitter.event().comment("heartbeat");
                // DB/Redis 读取可能跨越截止时间，不能在回来后继续向已关闭或过期的连接发送。
                if (connection.stopping || !connections.contains(connection)
                        || System.nanoTime() >= connection.expiresAt) break;
                connection.emitter.send(output);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            // 网络写入或来源检查失败后关闭连接，客户端可通过游标重新订阅。
        } finally {
            // 写线程退出后才归还许可；关闭请求不能让仍阻塞的网络写入绕过集群预算。
            finish(connection);
        }
    }

    private static BusinessException unavailable() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "进度服务正在关闭，请稍后重试。");
    }

    @Scheduled(scheduler = "sseScheduler", fixedDelayString = "${interview.runs.events.poll-ms:1000}")
    public void poll() {
        var groups = new HashMap<String, List<Connection>>();
        for (var c : connections) {
            if (c.stopping) continue;
            if (System.nanoTime() >= c.expiresAt) stop(c);
            else groups.computeIfAbsent(c.id, k -> new ArrayList<>()).add(c);
        }
        for (var group : groups.values()) {
            try {
                long after = group.stream().mapToLong(c -> c.seq).min().orElse(0);
                var first = group.getFirst();
                var events = store.events(first.owner, first.id, after);
                var row = store.get(first.owner, first.id);
                for (var c : group) {
                    for (var e : events)
                        if (e.seq() > c.seq) {
                            offer(c, e);
                            c.seq = e.seq();
                        }
                    if (System.nanoTime() - c.heartbeat > TimeUnit.SECONDS.toNanos(15)) {
                        offer(c, "heartbeat");
                        c.heartbeat = System.nanoTime();
                    }
                    if (row.terminal() && c.seq >= row.seq())
                        offer(c, "close");
                }
            } catch (RuntimeException ex) {
                group.forEach(this::stop);
            }
        }
    }

    private void offer(Connection c, Object event) {
        // 慢连接队列满时断开；持久事件可按客户端游标重放，不能阻塞其他连接的轮询。
        if (!c.queue.offer(event)) {
            stop(c);
        }
    }

    @PreDestroy
    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextClosedEvent.class)
    public void close() {
        List<Connection> closing;
        synchronized (lifecycle) {
            closed = true;
            closing = List.copyOf(connections);
        }
        closing.forEach(this::stop);
        writers.shutdownNow();
    }
}
