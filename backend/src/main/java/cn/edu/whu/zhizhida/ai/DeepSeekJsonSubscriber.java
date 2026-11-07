package cn.edu.whu.zhizhida.ai;

import cn.edu.whu.zhizhida.common.UpstreamServiceException;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** 在缓冲前拒绝超量字节；不依赖 Content-Length 或 SDK DTO 判断上限。 */
final class DeepSeekJsonSubscriber implements HttpResponse.BodySubscriber<String> {
    static final int MAX_BYTES = 1_048_576;
    private final CompletableFuture<String> body = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final int status;
    private Flow.Subscription subscription;

    DeepSeekJsonSubscriber(int status) { this.status = status; }
    public CompletionStage<String> getBody() { return body; }
    public void onSubscribe(Flow.Subscription value) {
        subscription = value;
        if (status < 200 || status >= 300) fail(UpstreamServiceException.coded("MODEL_HTTP_" + status));
        else value.request(1);
    }
    public void onNext(List<ByteBuffer> buffers) {
        if (body.isDone()) return;
        for (var buffer : buffers) {
            if (buffer.remaining() > MAX_BYTES - bytes.size()) {
                fail(UpstreamServiceException.coded("MODEL_RESPONSE_LIMIT"));
                return;
            }
            while (buffer.hasRemaining()) bytes.write(buffer.get());
        }
        subscription.request(1);
    }
    public void onComplete() {
        if (body.isDone()) return;
        try {
            body.complete(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString());
        } catch (java.nio.charset.CharacterCodingException ex) {
            fail(UpstreamServiceException.coded("MODEL_PROTOCOL", ex));
        }
    }
    public void onError(Throwable error) { fail(error); }
    private void fail(Throwable error) {
        body.completeExceptionally(error);
        if (subscription != null) subscription.cancel();
    }
}
