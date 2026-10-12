package com.goldcatcher;

import static org.assertj.core.api.Assertions.assertThat;
import com.goldcatcher.auth.ProofOfWork;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "gold.auth.username=contract-user", "gold.auth.password=contract-only", "gold.auth.pow-bits=8", "gold.source-commit=test-commit",
    "spring.datasource.url=jdbc:h2:mem:contract;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
class SystemContractTest {
    @LocalServerPort int port;
    private final JsonMapper mapper=JsonMapper.builder().build();
    private HttpClient client(){return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();}
    private HttpResponse<String> get(HttpClient client,String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(HttpClient client,String path,Object body,JsonNode token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json");
        if(token!=null)builder.header(token.path("csrfHeader").asText(),token.path("csrfToken").asText());
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private Map<String,String> solve(JsonNode packet) throws Exception {
        var c=packet.path("challenge");var challenge=new ProofOfWork.Challenge(c.path("id").asText(),c.path("nonce").asText(),c.path("difficulty").asInt(),120,"SHA-256");
        var hash=MessageDigest.getInstance("SHA-256");
        for(long i=0;;i++)if(ProofOfWork.valid(hash.digest(ProofOfWork.payload(challenge,Long.toString(i)).getBytes(StandardCharsets.UTF_8)),challenge.difficulty()))
            return Map.of("username","contract-user","password","contract-only","challengeId",challenge.id(),"solution",Long.toString(i));
    }
    private JsonNode login(HttpClient client) throws Exception {
        var packet=mapper.readTree(get(client,"/api/auth/challenge").body());
        assertThat(post(client,"/api/auth/login",solve(packet),packet).statusCode()).isEqualTo(200);
        return mapper.readTree(get(client,"/api/session").body());
    }
    @Test void sessionLoginRequiresProofAndCannotBeBypassedWithBasic() throws Exception {
        var client=client();assertThat(get(client,"/api/system/status").statusCode()).isEqualTo(401);
        var packet=mapper.readTree(get(client,"/api/auth/challenge").body());
        assertThat(post(client,"/api/auth/login",Map.of("username","contract-user","password","contract-only"),packet).statusCode()).isEqualTo(400);
        String basic=Base64.getEncoder().encodeToString("contract-user:contract-only".getBytes(StandardCharsets.UTF_8));
        assertThat(client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/session")).header("Authorization","Basic "+basic).build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        login(client);var response=get(client,"/api/system/status");assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("INTERNAL_SIMULATION_ALPHA","JAVA_CLOSED_BAR","WAITING_CURRENT_CFD_RULES","test-commit","GATE","SESSION_CSRF_SHA256_POW");
    }
    @Test void challengeIsSessionBoundSingleUseAndLoginRotatesSession() throws Exception {
        var client=client();var response=get(client,"/api/auth/challenge");var packet=mapper.readTree(response.body());
        var credentials=solve(packet);var other=client();var otherToken=mapper.readTree(get(other,"/api/auth/challenge").body());
        assertThat(post(other,"/api/auth/login",credentials,otherToken).statusCode()).isEqualTo(400);
        var logged=post(client,"/api/auth/login",credentials,packet);assertThat(logged.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("set-cookie").orElseThrow()).contains("HttpOnly","SameSite=Strict");
        assertThat(logged.headers().firstValue("set-cookie").orElseThrow()).isNotEqualTo(response.headers().firstValue("set-cookie").orElseThrow());
        var session=mapper.readTree(get(client,"/api/session").body());
        assertThat(post(client,"/api/auth/login",credentials,session).statusCode()).isEqualTo(400);
    }
    @Test void mutationsAndLogoutRequireCsrfThenInvalidateSession() throws Exception {
        var client=client();var token=login(client);
        assertThat(post(client,"/api/research",Map.of("symbol","XAU_USDT","horizon","SHORT"),null).statusCode()).isEqualTo(403);
        var invalid=post(client,"/api/research",Map.of("symbol","UNKNOWN","horizon","SHORT"),token);
        assertThat(invalid.statusCode()).isEqualTo(400);assertThat(invalid.body()).contains("UNKNOWN_PRODUCT");
        var cfd=mapper.readTree(get(client,"/api/dashboard?symbol=GATE:XAUUSD").body());
        assertThat(cfd.path("analysis").path("horizons")).hasSize(4);
        assertThat(cfd.path("analysis").path("method").asText()).isEqualTo("JAVA_STRUCTURE_V1_UNCALIBRATED");
        assertThat(cfd.path("analysis").path("sources")).hasSize(10);
        assertThat(cfd.path("market").path("quote").isNull()).isTrue();assertThat(cfd.path("account").path("balance").asText()).isEqualTo("1000");
        assertThat(cfd.path("account").path("equity").isNull()).isTrue();assertThat(cfd.path("marketProvenance").path("simulationReady").asBoolean()).isFalse();
        assertThat(post(client,"/api/auth/logout",Map.of(),null).statusCode()).isEqualTo(403);
        assertThat(post(client,"/api/auth/logout",Map.of(),token).statusCode()).isEqualTo(200);
        assertThat(get(client,"/api/dashboard").statusCode()).isEqualTo(401);
    }
    @Test void probeDoesNotExposeCredentialsOrStatus() throws Exception {
        var response=get(client(),"/actuator/health/readiness");assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("sourceCommit","password","contract-only");
    }
}
