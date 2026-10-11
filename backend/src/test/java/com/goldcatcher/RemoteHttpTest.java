package com.goldcatcher;

import static org.assertj.core.api.Assertions.*;
import com.github.luben.zstd.Zstd;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.zip.*;
import org.junit.jupiter.api.Test;

class RemoteHttpTest {
    @Test void streamHeartbeatDoesNotRemoveTheTotalDeadlineAndTimeoutsAreDistinguishable()throws Exception{
        for(String mode:java.util.List.of("first","idle","total")){
            var body=new RemoteHttp.LimitedBody(1000);long now=System.nanoTime();
            if(!mode.equals("first")){body.firstAt=now;body.lastAt=now;}
            var pending=new java.util.concurrent.CompletableFuture<String>();
            long total=java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(mode.equals("idle")?1000:80);
            long idle=java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(mode.equals("idle")?50:1000);
            assertThatThrownBy(()->RemoteHttp.awaitStream(pending,body,now,total,idle))
                .hasMessage(mode.equals("first")?"STREAM_FIRST_BYTE_TIMEOUT":mode.equals("idle")?"STREAM_IDLE_TIMEOUT":"STREAM_TOTAL_TIMEOUT");
        }
    }
    @Test void receivesFlushedSseChunksIncludingSplitUtf8AndReleasesTheConnectionAfterInterruption()throws Exception{
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);
        byte[] expected="data: 黄金\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);
        server.createContext("/sse",exchange->{try(exchange){exchange.getResponseHeaders().add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
            for(byte next:expected){exchange.getResponseBody().write(next);exchange.getResponseBody().flush();}
            try{Thread.sleep(3000);}catch(InterruptedException ignored){}}});
        server.createContext("/endless",exchange->{try(exchange){exchange.sendResponseHeaders(200,0);for(int i=0;i<40;i++){
            exchange.getResponseBody().write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));exchange.getResponseBody().flush();
            try{Thread.sleep(40);}catch(InterruptedException ignored){return;}}}});
        server.start();var http=new RemoteHttp();String base="http://127.0.0.1:"+server.getAddress().getPort();
        try{
            var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/sse")).header("Accept","text/event-stream").timeout(java.time.Duration.ofSeconds(2)).build();
            long started=System.nanoTime();assertThat(http.send(request,1000).body()).isEqualTo(expected);
            assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)).isLessThan(1500);
            assertThatThrownBy(()->http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/endless"))
                .header("Accept","text/event-stream").timeout(java.time.Duration.ofMillis(250)).build(),1000)).hasMessage("STREAM_TOTAL_TIMEOUT");
            assertThat(http.send(request,1000).body()).isEqualTo(expected);
        }finally{server.stop(0);executor.shutdownNow();http.client.shutdownNow();}
    }
    private final byte[] plain=("gold source "+"data ".repeat(500)).getBytes(StandardCharsets.UTF_8);
    @Test void decodesAllAdvertisedBrowserContentEncodingsAndEmptyConditionalResponses() throws Exception {
        var gzip=new ByteArrayOutputStream();try(var encoder=new GZIPOutputStream(gzip)){encoder.write(plain);}
        var deflate=new ByteArrayOutputStream();try(var encoder=new DeflaterOutputStream(deflate)){encoder.write(plain);}
        byte[] brotli=HexFormat.of().parseHex("1bcf09f8a541c2c8e8a4208cbc9605b4608a8dd60b002017");
        assertThat(RemoteHttp.decode(gzip.toByteArray(),"gzip",3000)).isEqualTo(plain);
        assertThat(RemoteHttp.decode(deflate.toByteArray(),"deflate",3000)).isEqualTo(plain);
        assertThat(RemoteHttp.decode(brotli,"br",3000)).isEqualTo(plain);
        assertThat(RemoteHttp.decode(Zstd.compress(plain),"zstd",3000)).isEqualTo(plain);
        assertThat(RemoteHttp.decode(new byte[0],"gzip",3000)).isEmpty();
    }
    @Test void capsDecompressedSizeAndRejectsUnsupportedOrCorruptResponses() throws Exception {
        assertThatThrownBy(()->RemoteHttp.decode(Zstd.compress(plain),"zstd",100)).hasMessage("RESPONSE_TOO_LARGE");
        assertThatThrownBy(()->RemoteHttp.decode(plain,"gzip",3000)).hasMessage("CONTENT_DECOMPRESSION_FAILED");
        assertThatThrownBy(()->RemoteHttp.decode(plain,"future-codec",3000)).hasMessage("CONTENT_ENCODING_UNSUPPORTED");
    }
    @Test void timeoutCoversStalledResponseBodyAndSubsequentRequestsStillWork() throws Exception {
        var release=new java.util.concurrent.CountDownLatch(1);
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);
        server.createContext("/stall",exchange->{try(exchange){exchange.sendResponseHeaders(200,10);exchange.getResponseBody().write('x');exchange.getResponseBody().flush();try{release.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}});
        server.createContext("/ok",exchange->{try(exchange){exchange.sendResponseHeaders(200,2);exchange.getResponseBody().write(new byte[]{'o','k'});}});
        server.start();var http=new RemoteHttp();String base="http://127.0.0.1:"+server.getAddress().getPort();
        try{
            var began=System.nanoTime();assertThatThrownBy(()->http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/stall")).timeout(java.time.Duration.ofMillis(500)).build(),100))
                .isInstanceOfAny(java.util.concurrent.TimeoutException.class,java.net.http.HttpTimeoutException.class);
            assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began)).isLessThan(3000);
            assertThat(http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/ok")).timeout(java.time.Duration.ofSeconds(2)).build(),100).body()).isEqualTo(new byte[]{'o','k'});
        }finally{release.countDown();server.stop(0);executor.shutdownNow();http.client.shutdownNow();}
    }
}
