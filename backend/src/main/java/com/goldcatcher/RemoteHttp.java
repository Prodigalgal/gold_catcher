package com.goldcatcher;

import java.io.ByteArrayOutputStream;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.stereotype.Component;

@Component
public class RemoteHttp {
    public final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    public HttpResponse<byte[]> send(HttpRequest request,int maximumBytes) throws Exception {
        var pending=client.sendAsync(request,info->new LimitedBody(maximumBytes));
        try { return pending.get(request.timeout().orElse(Duration.ofSeconds(20)).toMillis(),TimeUnit.MILLISECONDS); }
        catch(Exception error) { pending.cancel(true);throw error; }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> completion=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final int maximum;
        private Flow.Subscription subscription;
        private LimitedBody(int maximum) { this.maximum=maximum; }
        public CompletionStage<byte[]> getBody() { return completion; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription=subscription;subscription.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for(var buffer:buffers) {
                if(bytes.size()+buffer.remaining()>maximum) {
                    subscription.cancel();completion.completeExceptionally(new IllegalStateException("RESPONSE_TOO_LARGE"));return;
                }
                byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { completion.completeExceptionally(error); }
        public void onComplete() { completion.complete(bytes.toByteArray()); }
    }
}
