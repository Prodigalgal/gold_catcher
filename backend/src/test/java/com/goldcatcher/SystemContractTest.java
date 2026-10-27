package com.goldcatcher;

import static org.assertj.core.api.Assertions.assertThat;
import com.goldcatcher.auth.ProofOfWork;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "gold.auth.username=contract-user", "gold.auth.password=contract-only", "gold.auth.pow-bits=8", "gold.source-commit=test-commit",
    "spring.datasource.url=jdbc:h2:mem:contract;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
class SystemContractTest {
    @LocalServerPort int port;
    @Autowired EventHub events;
    @Autowired com.goldcatcher.auth.PersonalAccessTokens accessTokens;
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
        assertThat(response.headers().firstValue("set-cookie").orElseThrow()).contains("HttpOnly","SameSite=Lax");
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
        assertThat(cfd.path("analysis").path("sources")).hasSize(9);
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
    private HttpResponse<String> external(String method, String path, String secret, Object body, Map<String,String> headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Accept", "application/json, text/event-stream");
        if (secret != null) builder.header("Authorization", "Bearer " + secret);
        headers.forEach(builder::header);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : mapper.writeValueAsString(body)));
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private com.goldcatcher.auth.PersonalAccessTokens.Created access(String... scopes) {
        return accessTokens.create("contract-user", new com.goldcatcher.auth.PersonalAccessTokens.Create("HTTP contract", List.of(scopes), 1, 120));
    }
    @Test void tokenManagementRequiresOwnerSessionAndCsrfAndNeverReturnsExistingSecrets() throws Exception {
        var client = client(); var session = login(client);
        var body = Map.of("name", "Other AI", "scopes", List.of("market:read"), "expiresInDays", 30);
        assertThat(post(client, "/api/access/tokens", body, null).statusCode()).isEqualTo(403);
        var created = post(client, "/api/access/tokens", body, session); assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var data = mapper.readTree(created.body()); String secret = data.path("secret").asText(), id = data.path("token").path("id").asText();
        assertThat(get(client, "/api/access/tokens").body()).doesNotContain(secret, "token_hash");
        var publicRead = external("GET", "/api/external/v1/list_products", secret, null, Map.of());
        assertThat(publicRead.statusCode()).isEqualTo(200); assertThat(publicRead.headers().firstValue("set-cookie")).isEmpty();
        for (String path : List.of("/api/access/tokens", "/api/ai/configuration", "/api/session", "/api/dashboard"))
            assertThat(external("GET", path, secret, null, Map.of()).statusCode()).isEqualTo(401);
        assertThat(post(client, "/api/access/tokens/" + id + "/revoke", Map.of(), null).statusCode()).isEqualTo(403);
        assertThat(post(client, "/api/access/tokens/" + id + "/revoke", Map.of(), session).statusCode()).isEqualTo(200);
        assertThat(external("GET", "/api/external/v1/list_products", secret, null, Map.of()).statusCode()).isEqualTo(401);
        // An owner cookie is deliberately not a bearer credential for the external API.
        assertThat(get(client, "/api/external/v1").statusCode()).isEqualTo(401);
    }
    @Test void mcpLegacyHandshakeListsOnlyGrantedToolsAndEnforcesReadOnlyScope() throws Exception {
        var created = access("market:read"); String secret = created.secret();
        var init = external("POST", "/api/mcp", secret, Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params",
            Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of(), "clientInfo", Map.of("name", "test", "version", "1"))), Map.of());
        assertThat(init.statusCode()).isEqualTo(200); assertThat(mapper.readTree(init.body()).path("result").path("protocolVersion").asText()).isEqualTo("2025-11-25");
        assertThat(init.headers().firstValue("set-cookie")).isEmpty();
        var headers = Map.of("MCP-Protocol-Version", "2025-11-25");
        var notified = external("POST", "/api/mcp", secret, Map.of("jsonrpc", "2.0", "method", "notifications/initialized"), headers);
        assertThat(notified.statusCode()).isEqualTo(202); assertThat(notified.body()).isEmpty();
        var listed = external("POST", "/api/mcp", secret, Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list"), headers);
        assertThat(mapper.readTree(listed.body()).path("result").path("tools")).hasSize(5);
        var called = external("POST", "/api/mcp", secret, Map.of("jsonrpc", "2.0", "id", "call", "method", "tools/call", "params", Map.of("name", "list_products")), headers);
        assertThat(called.statusCode()).isEqualTo(200); assertThat(mapper.readTree(called.body()).path("result").path("structuredContent").path("products")).hasSize(2);
        var denied = external("POST", "/api/mcp", secret, Map.of("jsonrpc", "2.0", "id", 4, "method", "tools/call", "params", Map.of("name", "search_news")), headers);
        assertThat(denied.statusCode()).isEqualTo(403); assertThat(denied.body()).contains("INSUFFICIENT_SCOPE");
        assertThat(external("GET", "/api/external/v1/search_news", secret, null, Map.of()).statusCode()).isEqualTo(403);
        assertThat(external("POST", "/api/external/v1/get_quote", secret, Map.of(), Map.of()).statusCode()).isEqualTo(405);
        assertThat(external("POST", "/api/research", secret, Map.of("symbol", "XAU_USDT", "horizon", "SHORT"), Map.of()).statusCode()).isIn(401, 403);
        assertThat(external("POST", "/api/mcp", secret, Map.of("jsonrpc", "2.0", "id", 5, "method", "research/start"), headers).body()).contains("METHOD_NOT_FOUND");
        accessTokens.revoke("contract-user", created.token().id());
    }
    @Test void modernMcpMetadataHeadersOriginAndMalformedFramesAreValidated() throws Exception {
        var created = access("market:read"); String secret = created.secret();
        var meta = Map.of("io.modelcontextprotocol/protocolVersion", "2026-07-28", "io.modelcontextprotocol/clientCapabilities", Map.of());
        var body = Map.of("jsonrpc", "2.0", "id", 1, "method", "server/discover", "params", Map.of("_meta", meta));
        var headers = Map.of("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "server/discover");
        var discovery = external("POST", "/api/mcp", secret, body, headers);
        assertThat(discovery.statusCode()).isEqualTo(200); var result = mapper.readTree(discovery.body()).path("result");
        assertThat(result.path("resultType").asText()).isEqualTo("complete"); assertThat(result.path("supportedVersions").get(0).asText()).isEqualTo("2026-07-28");
        assertThat(result.path("cacheScope").asText()).isEqualTo("private"); assertThat(result.path("ttlMs").asInt(-1)).isZero();
        assertThat(external("POST", "/api/mcp", secret, body, Map.of("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "tools/list")).body()).contains("HEADER_MISMATCH");
        assertThat(external("POST", "/api/mcp", secret, body, Map.of("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "server/discover", "Origin", "https://hostile.example")).statusCode()).isEqualTo(403);
        assertThat(external("POST", "/api/mcp", secret, Map.of("jsonrpc", "2.0", "id", 1, "method", "ping"), Map.of("MCP-Protocol-Version", "2026-07-28")).body()).contains("REQUIRED_REQUEST_METADATA_MISSING");
        assertThat(external("POST", "/api/mcp", secret, "{", headers).body()).contains("PARSE_ERROR");
        assertThat(external("POST", "/api/mcp", secret, "[]", headers).body()).contains("INVALID_REQUEST");
        assertThat(external("POST", "/api/mcp", secret, mapper.writeValueAsString(body) + "{}", headers).body()).contains("PARSE_ERROR");
        assertThat(external("POST", "/api/mcp", secret, "x".repeat(32769), headers).statusCode()).isEqualTo(413);
        assertThat(external("GET", "/api/mcp", secret, null, Map.of()).statusCode()).isEqualTo(405);
        accessTokens.revoke("contract-user", created.token().id());
    }
    @Test void perTokenRateLimitReturns429AndDoesNotGrantOwnerPrivileges() throws Exception {
        var created = accessTokens.create("contract-user", new com.goldcatcher.auth.PersonalAccessTokens.Create("rate test", List.of("market:read"), 1, 10));
        for (int i = 0; i < 10; i++) assertThat(external("GET", "/api/external/v1/list_products", created.secret(), null, Map.of()).statusCode()).isEqualTo(200);
        var limited = external("GET", "/api/external/v1/list_products", created.secret(), null, Map.of());
        assertThat(limited.statusCode()).isEqualTo(429); assertThat(limited.headers().firstValue("Retry-After")).isPresent();
        accessTokens.revoke("contract-user", created.token().id());
        assertThat(external("POST", "/api/mcp", null, Map.of("jsonrpc", "2.0", "id", 1, "method", "ping"), Map.of()).statusCode()).isEqualTo(401);
    }
    @Test void openAiChatAndResponsesAdaptersUseIdenticalScopesAndPreserveToolCallIds() throws Exception {
        var created = access("market:read"); String secret = created.secret();
        var chat = external("GET", "/api/external/v1/openai/tools?format=chat", secret, null, Map.of());
        assertThat(chat.statusCode()).isEqualTo(200); var definitions = mapper.readTree(chat.body()).path("tools");
        assertThat(definitions).hasSize(5); assertThat(definitions.get(0).path("type").asText()).isEqualTo("function");
        assertThat(definitions.get(0).path("function").path("name").asText()).isEqualTo("list_products");
        var responseDefinitions = external("GET", "/api/external/v1/openai/tools?format=responses", secret, null, Map.of());
        assertThat(mapper.readTree(responseDefinitions.body()).path("tools").get(0).path("name").asText()).isEqualTo("list_products");
        var call = Map.of("id", "call_gpt_1", "type", "function", "function", Map.of("name", "list_products", "arguments", "{}"));
        var called = external("POST", "/api/external/v1/openai/tool", secret, Map.of("tool_call", call), Map.of());
        assertThat(called.statusCode()).isEqualTo(200); var result = mapper.readTree(called.body());
        assertThat(result.path("message").path("tool_call_id").asText()).isEqualTo("call_gpt_1");
        assertThat(result.path("message").path("role").asText()).isEqualTo("tool");
        assertThat(mapper.readTree(result.path("message").path("content").asText()).path("products")).hasSize(2);
        var responseCall = Map.of("type", "function_call", "call_id", "call_responses_1", "name", "list_products", "arguments", "{}");
        var output = external("POST", "/api/external/v1/openai/tool", secret, Map.of("tool_call", responseCall), Map.of());
        assertThat(mapper.readTree(output.body()).path("responseOutput").path("call_id").asText()).isEqualTo("call_responses_1");
        assertThat(mapper.readTree(output.body()).path("responseOutput").path("type").asText()).isEqualTo("function_call_output");
        var forbidden = Map.of("id", "call_gpt_2", "type", "function", "function", Map.of("name", "search_news", "arguments", "{}"));
        assertThat(external("POST", "/api/external/v1/openai/tool", secret, Map.of("tool_call", forbidden), Map.of()).statusCode()).isEqualTo(403);
        var invalid = Map.of("id", "call_bad", "type", "function", "function", Map.of("name", "list_products", "arguments", "{broken"));
        assertThat(external("POST", "/api/external/v1/openai/tool", secret, Map.of("tool_call", invalid), Map.of()).statusCode()).isEqualTo(400);
        accessTokens.revoke("contract-user", created.token().id());
    }
    @Test @ExtendWith(OutputCaptureExtension.class)
    void closingTheEventStreamAfterLogoutDoesNotAuthorizeAnAsyncCleanupAgain(CapturedOutput output)throws Exception {
        var client=client();assertThat(get(client,"/api/events").statusCode()).isEqualTo(401);var token=login(client);
        var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/events")).build(),HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new java.io.BufferedReader(new java.io.InputStreamReader(response.body(),StandardCharsets.UTF_8)).readLine()).isEqualTo("event:resync");
        assertThat(post(client,"/api/auth/logout",Map.of(),token).statusCode()).isEqualTo(200);response.body().close();
        org.awaitility.Awaitility.await().during(java.time.Duration.ofMillis(500)).atMost(java.time.Duration.ofSeconds(3)).untilAsserted(()->{
            events.publish();assertThat(output.getAll()).doesNotContain("Unable to handle the Spring Security Exception","AuthorizationDeniedException");
        });
        assertThat(get(client,"/api/events").statusCode()).isEqualTo(401);
    }
}
