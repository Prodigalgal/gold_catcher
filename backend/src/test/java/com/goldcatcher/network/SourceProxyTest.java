package com.goldcatcher.network;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.goldcatcher.*;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourceProxyTest {
    private final String node="vless://00000000-0000-0000-0000-000000000001@edge.example:443?type=ws&security=tls&sni=edge.example&host=upstream.example&path=%2Fws#label";
    @Test void subscriptionDeduplicatesAndIgnoresInvalidNodesWithoutDisablingTlsVerification() {
        var bytes=Base64.getEncoder().encode((node+"\n"+node+"\nvless://invalid@x:443\nvless://00000000-0000-0000-0000-000000000001@x:443?type=unsupported").getBytes(StandardCharsets.UTF_8));
        var nodes=VlessSubscription.parse(bytes);assertThat(nodes).hasSize(1);
        var tls=(Map<?,?>)nodes.getFirst().outbound().get("tls");assertThat(tls.get("insecure")).isEqualTo(false);
        assertThat(nodes.getFirst().id()).matches("[a-f0-9]{12}");
        assertThat(VlessSubscription.parse(node.replace("#label","#renamed").getBytes(StandardCharsets.UTF_8)).getFirst().id()).isEqualTo(nodes.getFirst().id());
        var config=VlessSubscription.config(nodes.getFirst(),15432);assertThat(config.toString()).contains("listen=127.0.0.1");
        assertThatThrownBy(()->VlessSubscription.parse("<html>Denied</html>".getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalStateException.class);
    }
    @Test void proxyIsOptionalAndOnlyRoutesExplicitSourceIdsWithoutExposingSubscription() throws Exception {
        var f=new TestSupport();var remote=mock(RemoteHttp.class);var pool=new SourceProxyPool(f.settings,remote,f.mapper,f.clock);
        f.environment.setProperty("GOLD_SOURCE_PROXY_SUBSCRIPTION_URL","https://private.example/sub?token=private-test-token");
        assertThat(pool.status().configured()).isTrue();assertThat(pool.status().enabled()).isFalse();
        assertThat(f.mapper.writeValueAsString(pool.status())).doesNotContain("private.example","private-test-token");
        var request=HttpRequest.newBuilder(URI.create("https://www.bls.gov/feed/cpi.rss")).GET().build();
        pool.send("BLS_CPI",request,1000);verify(remote).send(request,1000);
        f.environment.setProperty("GOLD_SOURCE_PROXY_ENABLED","true");
        assertThat(pool.routes("BLS_CPI")).isTrue();assertThat(pool.routes("GATE")).isFalse();assertThat(pool.routes("ANY2API")).isFalse();
        f.environment.setProperty("GOLD_SOURCE_PROXY_MODE","PROXY_ONLY");
        assertThatThrownBy(()->pool.send("BLS_CPI",request,1000)).hasMessage("PROXY_NOT_READY");
        pool.stop();
    }
    @Test void fallbackUsesDirectFirstAndDoesNotRotateNodesForRateLimitsOrContentProblems() throws Exception {
        var f=new TestSupport();var remote=mock(RemoteHttp.class);var pool=new SourceProxyPool(f.settings,remote,f.mapper,f.clock);
        f.environment.setProperty("GOLD_SOURCE_PROXY_ENABLED","true");var request=HttpRequest.newBuilder(URI.create("https://www.bls.gov/feed/cpi.rss")).GET().build();
        @SuppressWarnings("unchecked") var response=(java.net.http.HttpResponse<byte[]>)mock(java.net.http.HttpResponse.class);
        when(response.statusCode()).thenReturn(200);when(remote.send(request,1000)).thenReturn(response);
        assertThat(pool.send("BLS_CPI",request,1000)).isSameAs(response);assertThat(pool.status().targets().getFirst().route()).isEqualTo("DIRECT");
        when(response.statusCode()).thenReturn(429);assertThat(pool.send("BLS_CPI",request,1000).statusCode()).isEqualTo(429);
        when(remote.send(request,1000)).thenThrow(new IllegalStateException("RESPONSE_TOO_LARGE"));
        assertThatThrownBy(()->pool.send("BLS_CPI",request,1000)).hasMessage("RESPONSE_TOO_LARGE");
        doReturn(response).when(remote).send(request,1000);when(response.statusCode()).thenReturn(403);
        assertThatThrownBy(()->pool.send("BLS_CPI",request,1000)).hasMessage("PROXY_NOT_READY");
        assertThat(pool.routeName("BLS_CPI")).isEqualTo("DIRECT_WITH_CF_FALLBACK");pool.stop();
    }
}
