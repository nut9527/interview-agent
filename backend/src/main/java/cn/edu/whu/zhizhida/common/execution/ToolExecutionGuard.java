package cn.edu.whu.zhizhida.common.execution;

import cn.edu.whu.zhizhida.observability.AgentTelemetry;

import io.micrometer.tracing.Tracer;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 等待时间和活跃回调分别受限，忽略中断的回调在退出前继续占用名额。 */
@Component
public final class ToolExecutionGuard implements AutoCloseable {
    private final AgentTelemetry telemetry;
    private static final int DEFAULT_CAPACITY = 4;
    private final int capacity;
    private final Set<Thread> active = new HashSet<>();
    private boolean closed;

    @Autowired
    public ToolExecutionGuard(AgentTelemetry telemetry) {
        this.capacity = DEFAULT_CAPACITY;
        this.telemetry = telemetry;
    }

    public ToolExecutionGuard() {
        this(DEFAULT_CAPACITY);
    }

    public ToolExecutionGuard(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Invalid tool capacity");
        this.capacity = capacity;
        this.telemetry = new AgentTelemetry(Tracer.NOOP);
    }

    /** 在独立线程执行回调，不传播身份或事务；容量耗尽立即拒绝，超时只请求中断而不保证回调已退出。 */
    public <T> T call(long timeoutMillis, Callable<T> callback) {
        if (timeoutMillis <= 0) throw new RunFailureException("RUN_TOOL_BUDGET");
        if (Thread.currentThread().isInterrupted())
            throw new RunFailureException("TOOL_INTERRUPTED");
        var result =
                new FutureTask<T>(
                        telemetry.wrap(
                                () -> {
                                    try {
                                        return callback.call();
                                    } finally {
                                        synchronized (active) {
                                            active.remove(Thread.currentThread());
                                        }
                                    }
                                }));
        long started = System.nanoTime();
        var thread =
                Thread.ofVirtual()
                        .name("agent-tool")
                        .inheritInheritableThreadLocals(false)
                        .unstarted(
                                () -> {
                                    try {
                                        result.run();
                                    }
                                    // 任务在启动前已被取消时不会进入 callback，仍须在此释放名额。
                                    finally {
                                        synchronized (active) {
                                            active.remove(Thread.currentThread());
                                        }
                                    }
                                });
        synchronized (active) {
            if (closed) throw new RunFailureException("TOOL_UNAVAILABLE");
            if (active.size() >= capacity) throw new RunFailureException("TOOL_CAPACITY");
            active.add(thread);
            try {
                thread.start();
            } catch (RuntimeException | Error ex) {
                active.remove(thread);
                throw ex;
            }
        }
        try {
            long remaining =
                    TimeUnit.MILLISECONDS.toNanos(timeoutMillis) - (System.nanoTime() - started);
            if (remaining <= 0) throw new TimeoutException();
            return result.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            result.cancel(true);
            throw new RunFailureException("TOOL_TIMEOUT");
        } catch (InterruptedException ex) {
            result.cancel(true);
            Thread.currentThread().interrupt();
            throw new RunFailureException("TOOL_INTERRUPTED");
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) throw runtime;
            if (ex.getCause() instanceof Error error) throw error;
            throw new RunFailureException("TOOL_FAILED");
        }
    }

    @Override
    @PreDestroy
    public void close() {
        synchronized (active) {
            closed = true;
            active.forEach(Thread::interrupt);
        }
    }
}
