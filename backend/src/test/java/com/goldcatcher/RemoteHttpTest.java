package com.goldcatcher;

import static org.assertj.core.api.Assertions.*;
import com.github.luben.zstd.Zstd;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.zip.*;
import org.junit.jupiter.api.Test;

class RemoteHttpTest {
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
