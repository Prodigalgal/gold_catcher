package com.goldcatcher;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.URI;
import java.net.CookieManager;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "gold.auth.username=contract-user", "gold.auth.password=contract-only", "gold.source-commit=test-commit",
    "spring.datasource.url=jdbc:h2:mem:contract;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
class SystemContractTest {
    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void systemApiRequiresAuthenticationAndReportsActualCapabilities() throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + port + "/api/system/status");
        assertThat(client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString()).statusCode())
            .isEqualTo(401);
        String credential = Base64.getEncoder().encodeToString("contract-user:contract-only".getBytes(StandardCharsets.UTF_8));
        var response = client.send(HttpRequest.newBuilder(uri).header("Authorization", "Basic " + credential).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("INTERNAL_SIMULATION_ALPHA", "JAVA_CLOSED_BAR", "DISABLED", "WAITING_MT5", "test-commit");
    }

    @Test
    void probeDoesNotExposeSystemInformation() throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
            + "/actuator/health/readiness")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("sourceCommit", "password", "contract-only");
    }
    @Test
    void mutationsRequireCsrfAndProductAccountsStaySeparate() throws Exception {
        var cookies=new CookieManager();var sessionClient=HttpClient.newBuilder().cookieHandler(cookies).build();
        String credential=Base64.getEncoder().encodeToString("contract-user:contract-only".getBytes(StandardCharsets.UTF_8));
        String base="http://127.0.0.1:"+port+"/api/";
        var blocked=sessionClient.send(HttpRequest.newBuilder(URI.create(base+"research")).header("Authorization","Basic "+credential)
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"symbol\":\"XAUUSDT\",\"horizon\":\"SHORT\"}")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(blocked.statusCode()).isIn(401,403);
        var login=sessionClient.send(HttpRequest.newBuilder(URI.create(base+"session")).header("Authorization","Basic "+credential).build(),HttpResponse.BodyHandlers.ofString());
        var token=JsonMapper.builder().build().readTree(login.body());
        var invalid=sessionClient.send(HttpRequest.newBuilder(URI.create(base+"research")).header("Authorization","Basic "+credential)
            .header(token.path("csrfHeader").asText(),token.path("csrfToken").asText()).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"symbol\":\"UNKNOWN\",\"horizon\":\"SHORT\"}")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(invalid.statusCode()).isEqualTo(400);assertThat(invalid.body()).contains("UNKNOWN_PRODUCT");
        var cfd=sessionClient.send(HttpRequest.newBuilder(URI.create(base+"dashboard?symbol=XAUUSD.s")).header("Authorization","Basic "+credential).build(),HttpResponse.BodyHandlers.ofString());
        var cfdBody=JsonMapper.builder().build().readTree(cfd.body());assertThat(cfdBody.path("market").path("quote").isNull()).isTrue();assertThat(cfdBody.path("account").path("balance").isNull()).isTrue();
        var noHeader=sessionClient.send(HttpRequest.newBuilder(URI.create(base+"dashboard")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(noHeader.statusCode()).isEqualTo(401);
    }
}
