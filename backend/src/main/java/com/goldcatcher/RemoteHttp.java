package com.goldcatcher;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.http.*;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.stereotype.Component;
import org.brotli.dec.BrotliInputStream;
import com.github.luben.zstd.ZstdInputStream;

@Component
public class RemoteHttp {
    public final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ConcurrentHashMap<InetSocketAddress,HttpClient> proxies=new ConcurrentHashMap<>();
    public HttpResponse<byte[]> send(HttpRequest request,int maximumBytes) throws Exception {
        return send(client,request,maximumBytes,null);
    }
    public HttpResponse<byte[]> sendInference(HttpRequest request,int maximumBytes,java.util.function.Consumer<HttpResponse.ResponseInfo> headers) throws Exception {
        return send(client,request,maximumBytes,headers);
    }
    public HttpResponse<byte[]> send(HttpRequest request,int maximumBytes,InetSocketAddress proxy) throws Exception {
        var connection=proxies.computeIfAbsent(proxy,address->HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .proxy(ProxySelector.of(address)).build());
        return send(connection,request,maximumBytes,null);
    }
    public void forgetProxy(InetSocketAddress proxy) {var previous=proxies.remove(proxy);if(previous!=null)previous.shutdownNow();}
    private HttpResponse<byte[]> send(HttpClient connection,HttpRequest request,int maximumBytes,java.util.function.Consumer<HttpResponse.ResponseInfo> headers) throws Exception {
        boolean streaming=request.headers().firstValue("Accept").orElse("").contains("text/event-stream");
        var body=new LimitedBody(maximumBytes);
        long started=System.nanoTime(),duration=request.timeout().orElse(Duration.ofSeconds(20)).toNanos();
        var pending=connection.sendAsync(request,info->{
            if(headers!=null)headers.accept(info);
            String encoding=info.headers().firstValue("content-encoding").orElse("");
            body.stopAtDone=streaming&&info.statusCode()==200&&(encoding.isBlank()||encoding.equalsIgnoreCase("identity"));
            return HttpResponse.BodySubscribers.mapping(body,bytes->decode(bytes,encoding,maximumBytes));
        });
        try {
            if(!streaming)return pending.get(duration,TimeUnit.NANOSECONDS);
            return awaitStream(pending,body,started,duration,Duration.ofSeconds(120).toNanos());
        }
        catch(Exception error) {
            pending.cancel(true);if(error instanceof InterruptedException)Thread.currentThread().interrupt();
            Throwable cause=error;
            while((cause instanceof ExecutionException||cause instanceof CompletionException)&&cause.getCause()!=null)cause=cause.getCause();
            if(streaming&&cause instanceof HttpTimeoutException)
                throw new IllegalStateException(body.firstAt==0?"STREAM_FIRST_BYTE_TIMEOUT":"STREAM_TOTAL_TIMEOUT");
            if(cause instanceof Exception unwrapped)throw unwrapped;
            throw error;
        }
    }
    static <T> T awaitStream(CompletableFuture<T> pending,LimitedBody body,long started,long total,long idle)throws Exception {
        while(true){
            long now=System.nanoTime(),remaining=total-(now-started);
            if(remaining<=0)throw new IllegalStateException(body.firstAt==0?"STREAM_FIRST_BYTE_TIMEOUT":"STREAM_TOTAL_TIMEOUT");
            if(body.lastAt!=0&&now-body.lastAt>=idle)throw new IllegalStateException("STREAM_IDLE_TIMEOUT");
            try{return pending.get(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(250)),TimeUnit.NANOSECONDS);}
            catch(TimeoutException waiting){/* A heartbeat or reasoning delta keeps the idle clock alive, not the total deadline. */}
        }
    }
    static byte[] decode(byte[] bytes,String encoding,int maximum) {
        if(bytes.length==0||encoding.isBlank()||encoding.equalsIgnoreCase("identity"))return bytes;
        InputStream stream=new ByteArrayInputStream(bytes);
        try {
            String[] encodings=encoding.toLowerCase(java.util.Locale.ROOT).split(",");
            for(int i=encodings.length-1;i>=0;i--)stream=switch(encodings[i].strip()) {
                case "gzip","x-gzip" -> new java.util.zip.GZIPInputStream(stream);
                case "deflate" -> new java.util.zip.InflaterInputStream(stream);
                case "br" -> new BrotliInputStream(stream);
                case "zstd" -> new ZstdInputStream(stream).setLongMax(23);
                case "identity" -> stream;
                default -> throw new IllegalStateException("CONTENT_ENCODING_UNSUPPORTED");
            };
            try(var decoded=stream;var result=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192];int count;
                while((count=decoded.read(buffer))!=-1) {
                    if(result.size()+count>maximum)throw new IllegalStateException("RESPONSE_TOO_LARGE");
                    result.write(buffer,0,count);
                }
                return result.toByteArray();
            }
        }catch(IOException failure){throw new IllegalStateException("CONTENT_DECOMPRESSION_FAILED");}
        finally{try{stream.close();}catch(IOException ignored){}}
    }
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> completion=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final int maximum;
        private Flow.Subscription subscription;
        volatile long firstAt,lastAt;
        volatile boolean stopAtDone;
        private final StringBuilder linePrefix=new StringBuilder();private int lineLength;private boolean doneLine;
        private final SseTerminal terminal=new SseTerminal();
        LimitedBody(int maximum) { this.maximum=maximum; }
        public CompletionStage<byte[]> getBody() { return completion; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription=subscription;subscription.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for(var buffer:buffers) {
                if(buffer.hasRemaining()){long at=System.nanoTime();if(firstAt==0)firstAt=at;lastAt=at;}
                if(bytes.size()+buffer.remaining()>maximum) {
                    subscription.cancel();completion.completeExceptionally(new IllegalStateException("RESPONSE_TOO_LARGE"));return;
                }
                byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);
                if(stopAtDone&&(terminal.accept(chunk)||endsStream(chunk))){
                    // [DONE] is the SSE terminator; a provider need not close its TCP connection immediately.
                    completion.complete(bytes.toByteArray());subscription.cancel();return;
                }
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { completion.completeExceptionally(error); }
        public void onComplete() { completion.complete(bytes.toByteArray()); }
        private boolean endsStream(byte[] chunk){
            for(byte value:chunk){
                if(value=='\r')continue;
                if(value=='\n'){
                    if(lineLength==0&&doneLine)return true;
                    doneLine=lineLength==linePrefix.length()&&(linePrefix.toString().equals("data: [DONE]")||linePrefix.toString().equals("data:[DONE]"));
                    linePrefix.setLength(0);lineLength=0;
                }else{lineLength++;if(linePrefix.length()<32)linePrefix.append((char)(value&255));}
            }
            return false;
        }
    }
}
