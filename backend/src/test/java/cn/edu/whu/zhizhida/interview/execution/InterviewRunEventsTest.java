package cn.edu.whu.zhizhida.interview.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import cn.edu.whu.zhizhida.common.BusinessException;
import jakarta.servlet.AsyncEvent;

class InterviewRunEventsTest {
    private final InterviewRunStore store = mock(InterviewRunStore.class);
    private final SseConnectionBudget budget = new TestBudget();
    private final InterviewRunEvents events = new InterviewRunEvents(store, budget);

    // 模拟共享服务以测试连接生命周期；Lua 的跨客户端原子性由 Redis 集成测试覆盖。
    static class TestBudget extends SseConnectionBudget {
        private final HashMap<String, Long> leases = new HashMap<>();
        TestBudget() { super(null, "test", 8, 3); }
        @Override public synchronized String acquire(long owner) {
            if (leases.size() >= 8 || leases.values().stream().filter(v -> v == owner).count() >= 3)
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "full");
            String token = UUID.randomUUID().toString();
            leases.put(token, owner);
            return token;
        }
        @Override public synchronized void release(long owner, String token) { leases.remove(token, owner); }
        synchronized int active() { return leases.size(); }
    }

    @AfterEach
    void closeConnections() {
        events.close();
    }

    @RepeatedTest(5)
    void concurrentSubscriptionsRespectOwnerLimit() throws Exception {
        assertConcurrentAdmission(LongStream.range(0, 12).map(ignored -> 1).toArray(), 3);
    }

    @RepeatedTest(5)
    void concurrentSubscriptionsRespectSharedLimitAcrossOwners() throws Exception {
        assertConcurrentAdmission(LongStream.range(0, 12).map(i -> i / 2 + 1).toArray(), 8);
    }

    @Test
    void separateApiInstancesShareOwnerBudgetAndShutdownReturnsPermits() {
        var other = new InterviewRunEvents(store, budget);
        try {
            events.subscribe(1, "run", 0);
            other.subscribe(1, "run", 0);
            other.subscribe(1, "run", 0);
            assertFull();
            other.close();
            await().atMost(Duration.ofSeconds(3)).until(() -> ((TestBudget) budget).active() == 1);
            events.subscribe(1, "replacement", 0);
            events.subscribe(1, "replacement", 0);
            assertFull();
        } finally { other.close(); }
    }

    private void assertConcurrentAdmission(long[] owners, int acceptedLimit) throws Exception {
        var ready = new CyclicBarrier(owners.length);
        when(store.get(anyLong(), anyString())).thenAnswer(invocation -> {
            ready.await(5, TimeUnit.SECONDS);
            return null;
        });
        var callers = Executors.newFixedThreadPool(owners.length);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (long owner : owners) {
                results.add(callers.submit(() -> {
                    try {
                        events.subscribe(owner, "run-" + owner, 0);
                        return true;
                    } catch (BusinessException ex) {
                        assertThat(ex.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                        return false;
                    }
                }));
            }
            int accepted = 0;
            for (var result : results) {
                if (result.get(5, TimeUnit.SECONDS))
                    accepted++;
            }
            assertThat(accepted).isEqualTo(acceptedLimit);
            // 订阅只检查归属；超限请求不能触发事件查询、模型调用或状态变更。
            verify(store, times(owners.length)).get(anyLong(), anyString());
            verifyNoMoreInteractions(store);
        } finally {
            callers.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "completion", "timeout", "error" })
    void servletCallbacksReleaseOnlyTheirOwnConnection(String callback) throws Exception {
        events.subscribe(1, "run", 0);
        events.subscribe(1, "run", 0);
        var mvc = MockMvcBuilders.standaloneSetup(new EventsController(events)).build();
        var result = mvc.perform(get("/events")).andReturn();
        assertThat(result.getRequest().isAsyncStarted()).isTrue();
        assertFull();

        var context = (MockAsyncContext) result.getRequest().getAsyncContext();
        var signal = new AsyncEvent(context, new IOException("disconnected"));
        for (var listener : context.getListeners()) {
            switch (callback) {
                case "completion" -> listener.onComplete(signal);
                case "timeout" -> listener.onTimeout(signal);
                case "error" -> listener.onError(signal);
                default -> throw new AssertionError(callback);
            }
        }
        events.subscribe(1, "replacement", 0);
        assertFull();

        // 容器回调和写线程退出可能重复发生，不能释放新订阅占用的名额。
        for (var listener : context.getListeners())
            listener.onComplete(signal);
        assertFull();
    }

    @Test
    void terminalRunReleasesAllConnections() {
        var row = mock(InterviewRunStore.Row.class);
        when(row.terminal()).thenReturn(true);
        when(store.get(1, "finished")).thenReturn(row);
        when(store.events(1, "finished", 0)).thenReturn(List.of());
        for (int i = 0; i < 3; i++)
            events.subscribe(1, "finished", 0);

        events.poll();
        verify(store).events(1, "finished", 0);
        var replacements = new AtomicInteger();
        await().atMost(Duration.ofSeconds(3)).until(() -> {
            try {
                events.subscribe(1, "replacement", 0);
                return replacements.incrementAndGet() == 3;
            } catch (BusinessException ex) {
                assertThat(ex.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                return false;
            }
        });
        assertFull();
    }

    @Test
    void failedEventReadReleasesConnectionsWithoutWaitingForServletCallbacks() {
        for (int i = 0; i < 3; i++)
            events.subscribe(1, "removed", 0);
        when(store.events(1, "removed", 0)).thenThrow(new IllegalStateException("source removed"));

        events.poll();
        await().atMost(Duration.ofSeconds(3)).until(() -> ((TestBudget) budget).active() == 0);
        for (int i = 0; i < 3; i++)
            events.subscribe(1, "replacement", 0);
        assertFull();
    }

    @Test
    void shutdownRejectsNewConnectionsAndCanBeRepeated() {
        events.subscribe(1, "run", 0);
        events.close();
        events.close();
        assertThatThrownBy(() -> events.subscribe(1, "run", 0))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void blockedNetworkWriteCannotBlockPollingOrShutdownAndKeepsItsPermit() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var releaseWrite = new java.util.concurrent.CountDownLatch(1);
        var emitter = new SseEmitter() {
            private final Object writeLock = new Object();
            @Override public void send(SseEventBuilder event) {
                synchronized (writeLock) {
                    entered.countDown();
                    boolean interrupted = false;
                    while (releaseWrite.getCount() != 0) {
                        try { releaseWrite.await(); }
                        catch (InterruptedException ignored) { interrupted = true; }
                    }
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
            @Override public void complete() {
                synchronized (writeLock) { super.complete(); }
            }
        };
        var blocked = new InterviewRunEvents(store, budget) {
            @Override SseEmitter createEmitter() { return emitter; }
        };
        var calls = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var row = mock(InterviewRunStore.Row.class);
            when(store.get(1, "slow")).thenReturn(row);
            when(store.events(1, "slow", 0)).thenReturn(List.of(
                    new cn.edu.whu.zhizhida.interview.api.InterviewRun.Event("slow", 1, "run.progress", "MODEL", "", null)));
            blocked.subscribe(1, "slow", 0);
            blocked.poll();
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            when(store.events(1, "slow", 1)).thenThrow(new IllegalStateException("source removed"));
            calls.submit(blocked::poll).get(3, TimeUnit.SECONDS);
            calls.submit(blocked::close).get(3, TimeUnit.SECONDS);
            assertThat(((TestBudget) budget).active()).isEqualTo(1);
            releaseWrite.countDown();
            await().atMost(Duration.ofSeconds(3)).until(() -> ((TestBudget) budget).active() == 0);
        } finally {
            releaseWrite.countDown();
            blocked.close();
            calls.shutdownNow();
        }
    }

    private void assertFull() {
        assertThatThrownBy(() -> events.subscribe(1, "overflow", 0))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @RestController
    static class EventsController {
        private final InterviewRunEvents events;

        EventsController(InterviewRunEvents events) {
            this.events = events;
        }

        @GetMapping("/events")
        SseEmitter subscribe() {
            return events.subscribe(1, "run", 0);
        }
    }
}
