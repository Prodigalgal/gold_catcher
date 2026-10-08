package com.goldcatcher;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "gold.auth.username=contract-user", "gold.auth.password=contract-only", "gold.source-commit=test-commit"
})
class SystemContractTest {
    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void systemApiRequiresAuthenticationAndReportsUnimplementedTrading() throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + port + "/api/system/status");
        assertThat(client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString()).statusCode())
            .isEqualTo(401);
        String credential = Base64.getEncoder().encodeToString("contract-user:contract-only".getBytes(StandardCharsets.UTF_8));
        var response = client.send(HttpRequest.newBuilder(uri).header("Authorization", "Basic " + credential).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("DESIGN_PROTOTYPE", "NOT_IMPLEMENTED", "test-commit");
    }

    @Test
    void probeDoesNotExposeSystemInformation() throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
            + "/actuator/health/readiness")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("sourceCommit", "password", "contract-only");
    }
}
