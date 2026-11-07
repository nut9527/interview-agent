package compatibility;

import dev.langchain4j.http.client.*;
import dev.langchain4j.http.client.sse.*;
import java.time.Duration;
import java.util.concurrent.*;

/** M0 验证：通过 SDK 异步取消，以同一个总截止时间约束响应头和响应体。
 * 仅用于测试；生产环境还须限制响应体解码大小，并支持取消 Run。
 */
final class DeadlineProbe implements HttpClientBuilder, AutoCloseable {
    private final HttpClientBuilder delegate;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("m0-lc-deadline").factory());
    DeadlineProbe(HttpClientBuilder delegate) { this.delegate = delegate; }
    public Duration connectTimeout() { return delegate.connectTimeout(); }
    public HttpClientBuilder connectTimeout(Duration value) { delegate.connectTimeout(value); return this; }
    public Duration readTimeout() { return delegate.readTimeout(); }
    public HttpClientBuilder readTimeout(Duration value) { delegate.readTimeout(value); return this; }
    public void close() { timer.shutdownNow(); }

    public HttpClient build() {
        var client = delegate.build();
        long timeoutNanos = readTimeout().toNanos();
        return new HttpClient() {
            public SuccessfulHttpResponse execute(HttpRequest request) {
                var future = client.executeAsync(request);
                try { return future.get(timeoutNanos, TimeUnit.NANOSECONDS); }
                catch (InterruptedException ex) {
                    future.cancel(true);
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("MODEL_INTERRUPTED", ex);
                } catch (TimeoutException ex) {
                    future.cancel(true);
                    throw new IllegalStateException("MODEL_DEADLINE", ex);
                } catch (ExecutionException ex) { throw new IllegalStateException("MODEL_HTTP_FAILED", ex.getCause()); }
            }

            public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
                client.stream(request, parser).subscribe(new Flow.Subscriber<>() {
                    private Flow.Subscription subscription;
                    private ScheduledFuture<?> deadline;
                    private boolean terminated;
                    private boolean cancelled;
                    private final ServerSentEventContext context = new ServerSentEventContext(new ServerSentEventParsingHandle() {
                        public void cancel() { cancelStream(); }
                        public boolean isCancelled() { synchronized (DeadlineProbe.this) { return cancelled; } }
                    });

                    public void onSubscribe(Flow.Subscription value) {
                        synchronized (DeadlineProbe.this) {
                            subscription = value;
                            deadline = timer.schedule(() -> onError(new IllegalStateException("MODEL_DEADLINE")),
                                    timeoutNanos, TimeUnit.NANOSECONDS);
                        }
                        value.request(1);
                    }
                    private void cancelStream() {
                        synchronized (DeadlineProbe.this) {
                            if (terminated) return;
                            terminated = true;
                            cancelled = true;
                            deadline.cancel(false);
                            subscription.cancel();
                        }
                    }
                    public void onNext(HttpStreamingEvent event) {
                        synchronized (DeadlineProbe.this) {
                            if (terminated) return;
                            try {
                                if (event instanceof HttpResponseReceived headers) listener.onOpen(headers.response());
                                else if (event instanceof ServerSentEvent sse) listener.onEvent(sse, context);
                                else if (event instanceof HttpErrorResponseReceived error)
                                    throw new IllegalStateException("MODEL_HTTP_" + error.statusCode());
                                if (!terminated) subscription.request(1);
                            } catch (RuntimeException error) { onError(error); }
                        }
                    }
                    public void onError(Throwable error) {
                        synchronized (DeadlineProbe.this) {
                            if (terminated) return;
                            cancelStream();
                            listener.onError(error);
                        }
                    }
                    public void onComplete() {
                        synchronized (DeadlineProbe.this) {
                            if (terminated) return;
                            terminated = true;
                            deadline.cancel(false);
                            listener.onClose();
                        }
                    }
                });
            }
        };
    }
}
