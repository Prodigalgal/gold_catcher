package com.goldcatcher;

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
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "gold.auth.username=oauth-owner", "gold.auth.password=oauth-test-only", "gold.auth.pow-bits=8", "gold.oauth.issuer=https://gold.example",
    "spring.datasource.url=jdbc:h2:mem:oauth-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
class OAuthHttpContractTest {
    @LocalServerPort int port;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private static final String CALLBACK = "https://chatgpt.com/connector_platform_oauth_redirect", RESOURCE = "https://gold.example/api/mcp", VERIFIER = "x".repeat(64);
    private HttpClient browser() { return HttpClient.newBuilder().cookieHandler(new CookieManager()).build(); }
    private HttpResponse<String> send(HttpClient client, String method, String path, String body, String type, Map<String,String> headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if (type != null) builder.header("Content-Type", type); headers.forEach(builder::header);
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> get(HttpClient client, String path) throws Exception { return send(client,"GET",path,null,null,Map.of()); }
    private HttpResponse<String> json(HttpClient client, String path, Object body, JsonNode csrf) throws Exception {
        return send(client,"POST",path,mapper.writeValueAsString(body),"application/json",csrf==null?Map.of():Map.of(csrf.path("csrfHeader").asText(),csrf.path("csrfToken").asText()));
    }
    private String encode(Map<String,String> values) { return values.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&")); }
    private Map<String,String> query(String location) {
        var values = new HashMap<String,String>(); for(var part:URI.create(location).getRawQuery().split("&")){var pair=part.split("=",2);values.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),URLDecoder.decode(pair[1],StandardCharsets.UTF_8));}return values;
    }
    private JsonNode login(HttpClient browser) throws Exception {
        var packet=mapper.readTree(get(browser,"/api/auth/challenge").body());var c=packet.path("challenge");
        var challenge=new ProofOfWork.Challenge(c.path("id").asText(),c.path("nonce").asText(),c.path("difficulty").asInt(),120,"SHA-256");
        var hash=MessageDigest.getInstance("SHA-256");String solution=null;
        for(long i=0;solution==null;i++)if(ProofOfWork.valid(hash.digest(ProofOfWork.payload(challenge,Long.toString(i)).getBytes(StandardCharsets.UTF_8)),challenge.difficulty()))solution=Long.toString(i);
        assertThat(json(browser,"/api/auth/login",Map.of("username","oauth-owner","password","oauth-test-only","challengeId",challenge.id(),"solution",solution),packet).statusCode()).isEqualTo(200);
        return mapper.readTree(get(browser,"/api/session").body());
    }
    private String client(HttpClient browser) throws Exception {
        var registration=json(browser,"/api/oauth/register",Map.of("client_name","ChatGPT contract","redirect_uris",List.of(CALLBACK),"token_endpoint_auth_method","none"),null);
        assertThat(registration.statusCode()).isEqualTo(201); assertThat(registration.headers().allValues("set-cookie")).isEmpty();
        var body=mapper.readTree(registration.body()); assertThat(body.has("client_secret")).isFalse();return body.path("client_id").asText();
    }
    private String authorize(HttpClient browser,String client) throws Exception {
        var challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
        var response=get(browser,"/api/oauth/authorize?"+encode(Map.of("client_id",client,"response_type","code","redirect_uri",CALLBACK,"resource",RESOURCE,"scope","market:read information:read","state","state /?& +","code_challenge",challenge,"code_challenge_method","S256")));
        assertThat(response.statusCode()).isEqualTo(303); return query(response.headers().firstValue("location").orElseThrow()).get("request");
    }
    @Test void realPoWSessionConsentCodeExchangeAndScopedMcpWorkEndToEnd() throws Exception {
        var browser=browser();String client=client(browser),ticket=authorize(browser,client);
        var anonymous=mapper.readTree(get(browser,"/api/oauth/consent?request="+ticket).body()); assertThat(anonymous.path("authenticated").asBoolean()).isFalse();
        assertThat(get(browser(),"/api/oauth/consent?request="+ticket).statusCode()).isEqualTo(400);
        var csrf=login(browser); // Session rotation must preserve the pending, browser-bound request.
        var consent=Map.of("request",ticket,"decision","approve","scopes",List.of("market:read"));
        assertThat(json(browser,"/api/oauth/consent",consent,null).statusCode()).isEqualTo(403);
        var approved=json(browser,"/api/oauth/consent",consent,csrf);assertThat(approved.statusCode()).isEqualTo(200);
        var callback=query(mapper.readTree(approved.body()).path("redirectUrl").asText());
        assertThat(callback.get("state")).isEqualTo("state /?& +");assertThat(callback.get("iss")).isEqualTo("https://gold.example");
        assertThat(json(browser,"/api/oauth/consent",consent,csrf).statusCode()).isEqualTo(400);
        var parameters=Map.of("grant_type","authorization_code","client_id",client,"redirect_uri",CALLBACK,"resource",RESOURCE,"code",callback.get("code"),"code_verifier",VERIFIER);
        String form=encode(parameters);
        assertThat(send(browser,"POST","/api/oauth/token",form+"&resource=duplicate","application/x-www-form-urlencoded",Map.of()).statusCode()).isEqualTo(400);
        var token=send(browser,"POST","/api/oauth/token",form,"application/x-www-form-urlencoded",Map.of());assertThat(token.statusCode()).isEqualTo(200);assertThat(token.headers().allValues("set-cookie")).isEmpty();
        var access=mapper.readTree(token.body()).path("access_token").asText();
        var headers=Map.of("Authorization","Bearer "+access,"Accept","application/json, text/event-stream","MCP-Protocol-Version","2025-11-25");
        var tools=send(browser(),"POST","/api/mcp","{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}","application/json",headers);
        assertThat(tools.statusCode()).isEqualTo(200);assertThat(mapper.readTree(tools.body()).path("result").path("tools").size()).isEqualTo(5);
        assertThat(tools.body()).contains("securitySchemes","oauth2","market:read");
        var denied=send(browser(),"GET","/api/external/v1/search_news",null,null,headers);
        assertThat(denied.statusCode()).isEqualTo(403);assertThat(denied.headers().firstValue("www-authenticate").orElseThrow()).contains("insufficient_scope", "scope=\"information:read\"");
        assertThat(send(browser(),"GET","/api/session",null,null,headers).statusCode()).isEqualTo(401);
        var grants=mapper.readTree(get(browser,"/api/access/oauth").body()).path("authorizations");assertThat(grants.toString()).doesNotContain(access,callback.get("code"));
        var grant=grants.get(0).path("id").asText();
        assertThat(json(browser,"/api/access/oauth/"+grant+"/revoke",Map.of(),null).statusCode()).isEqualTo(403);
        assertThat(json(browser,"/api/access/oauth/"+grant+"/revoke",Map.of(),csrf).statusCode()).isEqualTo(200);
        assertThat(send(browser(),"GET","/api/external/v1/list_products",null,null,headers).statusCode()).isEqualTo(401);
    }
    @Test void discoveryChallengeAndDenialAreStandardsCompatibleAndDoNotIssueTokens() throws Exception {
        var browser=browser();var response=get(browser,"/.well-known/oauth-protected-resource/api/mcp");assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).path("resource").asText()).isEqualTo(RESOURCE);
        var server=mapper.readTree(get(browser,"/.well-known/oauth-authorization-server").body());
        assertThat(server.path("code_challenge_methods_supported").get(0).asText()).isEqualTo("S256");
        assertThat(server.path("authorization_response_iss_parameter_supported").asBoolean()).isTrue();
        var unauthorized=get(browser(),"/api/mcp");assertThat(unauthorized.statusCode()).isEqualTo(401);
        assertThat(unauthorized.headers().firstValue("www-authenticate").orElseThrow()).contains("resource_metadata=\"https://gold.example/.well-known/oauth-protected-resource/api/mcp\"");
        String ticket=authorize(browser,client(browser));var csrf=login(browser);
        var denied=json(browser,"/api/oauth/consent",Map.of("request",ticket,"decision","deny"),csrf);
        var callback=query(mapper.readTree(denied.body()).path("redirectUrl").asText());assertThat(callback).containsEntry("error","access_denied").containsEntry("iss","https://gold.example");assertThat(callback).doesNotContainKey("code");
        assertThat(json(browser(),"/api/oauth/register",Map.of("redirect_uris",List.of("http://evil.example/callback")),null).statusCode()).isEqualTo(400);
    }
    @Test void permanentPatCreationAndConversionRequireOwnerSessionAndCsrf() throws Exception {
        var owner=browser();var csrf=login(owner);
        var created=json(owner,"/api/access/tokens",Map.of("name","Permanent HTTP contract","scopes",List.of("market:read"),"expiresInDays",0),csrf);
        assertThat(created.statusCode()).isEqualTo(201);var packet=mapper.readTree(created.body());assertThat(packet.path("token").path("expiresAt").isNull()).isTrue();
        var finite=mapper.readTree(json(owner,"/api/access/tokens",Map.of("name","Finite HTTP contract","scopes",List.of("market:read"),"expiresInDays",365),csrf).body());
        String path="/api/access/tokens/"+finite.path("token").path("id").asText()+"/permanent";
        assertThat(json(owner,path,Map.of(),null).statusCode()).isEqualTo(403);
        var changed=json(owner,path,Map.of(),csrf);assertThat(changed.statusCode()).isEqualTo(200);assertThat(mapper.readTree(changed.body()).path("expiresAt").isNull()).isTrue();
        var read=send(browser(),"GET","/api/external/v1/list_products",null,null,Map.of("Authorization","Bearer "+finite.path("secret").asText()));assertThat(read.statusCode()).isEqualTo(200);
        var inventory=get(owner,"/api/access/tokens");assertThat(inventory.body()).doesNotContain(packet.path("secret").asText(),finite.path("secret").asText());
    }
}
