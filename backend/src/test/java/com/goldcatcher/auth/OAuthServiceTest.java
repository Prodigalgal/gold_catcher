package com.goldcatcher.auth;

import com.goldcatcher.TestSupport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import static org.assertj.core.api.Assertions.*;

class OAuthServiceTest {
    private final TestSupport t = new TestSupport();
    private final InMemoryUserDetailsManager users = new InMemoryUserDetailsManager(User.withUsername("owner").password("{noop}test-only").roles("OWNER").build());
    private final PersonalAccessTokens pats = new PersonalAccessTokens(t.jdbc,t.clock,t.store);
    private final OAuthService oauth = service();
    private static final String ISSUER = "https://gold.example", CALLBACK = "https://chatgpt.com/connector_platform_oauth_redirect";
    private static final String VERIFIER = "v".repeat(64);
    private OAuthService service() { return new OAuthService(t.jdbc, t.store, t.mapper, t.clock, users, pats, ISSUER); }
    private String challenge() throws Exception { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII))); }
    private OAuthService.Client client() { return oauth.register("ChatGPT", List.of(CALLBACK), null); }
    private Map<String,String> request(OAuthService.Client client) throws Exception {
        return new HashMap<>(Map.of("client_id", client.id(), "redirect_uri", CALLBACK, "response_type", "code", "scope", "market:read information:read", "resource", oauth.resource(), "state", "client-state", "code_challenge", challenge(), "code_challenge_method", "S256"));
    }
    private OAuthService.Issued issue(OAuthService.Client client) throws Exception { return oauth.approve(pat(List.of("market:read", "information:read")), oauth.authorize(request(client))); }
    private String pat(List<String> scopes){return pats.create("owner",new PersonalAccessTokens.Create("OAuth backing test",scopes,0,120)).secret();}
    private Map<String,String> exchange(OAuthService.Client client, String code) { return new HashMap<>(Map.of("grant_type", "authorization_code", "client_id", client.id(), "code", code, "code_verifier", VERIFIER, "redirect_uri", CALLBACK, "resource", oauth.resource())); }
    private Map<String,String> refresh(OAuthService.Client client, Object refresh) { return new HashMap<>(Map.of("grant_type", "refresh_token", "client_id", client.id(), "refresh_token", refresh.toString(), "resource", oauth.resource())); }
    @Test void persistentOpaqueTokensAreHashedAndCanUseOnlyConsentedScopes() throws Exception {
        var client = client(); var issued = issue(client); var tokens = oauth.exchange(exchange(client, issued.code()));
        String access = (String)tokens.get("access_token"), refresh = (String)tokens.get("refresh_token");
        var identity = service().authenticate(access);
        assertThat(identity.owner()).isEqualTo("owner"); assertThat(identity.scopes()).containsExactlyInAnyOrder("market:read", "information:read");
        assertThatThrownBy(() -> identity.require("research:read")).hasMessage("INSUFFICIENT_SCOPE");
        assertThat(tokens.get("expires_in")).isEqualTo(3600L); assertThat(tokens.get("token_type")).isEqualTo("Bearer");
        String database = t.jdbc.queryForList("SELECT * FROM gc_oauth_token").toString() + t.jdbc.queryForList("SELECT * FROM gc_oauth_code") + t.jdbc.queryForList("SELECT * FROM gc_event");
        assertThat(database).doesNotContain(access, refresh, issued.code(), VERIFIER);
        oauth.used(identity); assertThat(oauth.list("owner").getFirst().useCount()).isEqualTo(1);
        assertThat(oauth.list("another-owner")).isEmpty();
    }
    @Test void authorizationCodeIsSingleUseAndBadVerifierDoesNotConsumeIt() throws Exception {
        var client = client(); var code = issue(client).code(); var exchange = exchange(client, code);
        exchange.put("code_verifier", "wrong".repeat(12)); assertThatThrownBy(() -> oauth.exchange(exchange)).hasMessage("invalid_grant");
        exchange.put("code_verifier", VERIFIER); oauth.exchange(exchange);
        assertThatThrownBy(() -> oauth.exchange(exchange)).hasMessage("invalid_grant");
        assertThat(t.jdbc.queryForObject("SELECT COUNT(*) FROM gc_oauth_token", Integer.class)).isEqualTo(2);
    }
    @Test void clientCallbackAndAudienceCannotBeSubstituted() throws Exception {
        var client = client(); var code = issue(client).code();
        var wrongClient = exchange(client(), code); assertThatThrownBy(() -> oauth.exchange(wrongClient)).hasMessage("invalid_grant");
        var badCallback = exchange(client, code); badCallback.put("redirect_uri", CALLBACK + "/evil"); assertThatThrownBy(() -> oauth.exchange(badCallback)).hasMessage("invalid_grant");
        var wrongResource = exchange(client, code); wrongResource.put("resource", "https://other.example/api/mcp"); assertThatThrownBy(() -> oauth.exchange(wrongResource)).hasMessage("invalid_target");
        var missingResource = exchange(client, code); missingResource.remove("resource"); assertThatThrownBy(() -> oauth.exchange(missingResource)).hasMessage("invalid_target");
        assertThat(oauth.exchange(exchange(client, code))).containsKey("access_token");
    }
    @Test void refreshRotationAndReplayRevokeTheWholeFamilyEvenAfterRestart() throws Exception {
        var client = client(); var issued = issue(client); var first = oauth.exchange(exchange(client, issued.code()));
        var next = service().exchange(refresh(client, first.get("refresh_token")));
        assertThat(next.get("refresh_token")).isNotEqualTo(first.get("refresh_token"));
        assertThat(oauth.authenticate((String)next.get("access_token"))).isNotNull();
        assertThatThrownBy(() -> oauth.exchange(refresh(client, first.get("refresh_token")))).hasMessage("invalid_grant");
        assertThat(oauth.list("owner").getFirst().state()).isEqualTo("REVOKED");
        assertThatThrownBy(() -> service().authenticate((String)next.get("access_token"))).hasMessage("INVALID_ACCESS_TOKEN");
        assertThatThrownBy(() -> oauth.exchange(refresh(client, next.get("refresh_token")))).hasMessage("invalid_grant");
        assertThat(t.jdbc.queryForObject("SELECT COUNT(*) FROM gc_event WHERE event_type='OAUTH_REFRESH_REPLAY'", Integer.class)).isEqualTo(1);
    }
    @Test void refreshCanNarrowButNeverExpandItsScopes() throws Exception {
        var client = client(); var first = oauth.exchange(exchange(client, issue(client).code()));
        var expanded = refresh(client, first.get("refresh_token")); expanded.put("scope", "research:read");
        assertThatThrownBy(() -> oauth.exchange(expanded)).hasMessage("invalid_scope");
        var narrower = refresh(client, first.get("refresh_token")); narrower.put("scope", "market:read"); var next = oauth.exchange(narrower);
        assertThat(oauth.authenticate((String)next.get("access_token")).scopes()).containsExactly("market:read");
        var broaden = refresh(client, next.get("refresh_token")); broaden.put("scope", "market:read information:read");
        assertThatThrownBy(() -> oauth.exchange(broaden)).hasMessage("invalid_scope");
    }
    @Test void ownerRevocationIsIsolatedAndImmediateAndDoesNotAffectPat() throws Exception {
        var client = client(); var issued = issue(client); var tokens = oauth.exchange(exchange(client, issued.code()));
        var pat = new PersonalAccessTokens(t.jdbc, t.clock, t.store); var existing = pat.create("owner", new PersonalAccessTokens.Create("Bearer retained", List.of("market:read"), 90, 120));
        assertThatThrownBy(() -> oauth.revoke("another-owner", issued.grantId())).hasMessage("authorization_not_found");
        oauth.revoke("owner", issued.grantId()); oauth.revoke("owner", issued.grantId());
        assertThatThrownBy(() -> oauth.authenticate((String)tokens.get("access_token"))).hasMessage("INVALID_ACCESS_TOKEN");
        assertThatThrownBy(() -> oauth.exchange(refresh(client, tokens.get("refresh_token")))).hasMessage("invalid_grant");
        assertThat(pat.authenticate(existing.secret()).owner()).isEqualTo("owner");
    }
    @Test void revocationEndpointIsClientBoundAndUnknownTokensAreIdempotent() throws Exception {
        var client = client(); var issued = issue(client); var tokens = oauth.exchange(exchange(client, issued.code()));
        oauth.revokeToken(client().id(), (String)tokens.get("access_token")); assertThat(oauth.authenticate((String)tokens.get("access_token"))).isNotNull();
        oauth.revokeToken(client.id(), "unknown"); oauth.revokeToken(client.id(), (String)tokens.get("refresh_token"));
        assertThatThrownBy(() -> oauth.authenticate((String)tokens.get("access_token"))).hasMessage("INVALID_ACCESS_TOKEN");
    }
    @Test void deadlinesAndDisabledOwnerAreEnforced() throws Exception {
        var client = client(); var expiredCode = issue(client); t.clock.advance(121000);
        assertThatThrownBy(() -> oauth.exchange(exchange(client, expiredCode.code()))).hasMessage("invalid_grant");
        var tokens = oauth.exchange(exchange(client, issue(client).code())); t.clock.advance(3601000);
        assertThatThrownBy(() -> oauth.authenticate((String)tokens.get("access_token"))).hasMessage("INVALID_ACCESS_TOKEN");
        var next = oauth.exchange(refresh(client, tokens.get("refresh_token"))); assertThat(next).containsKey("access_token");
        users.updateUser(User.withUsername("owner").password("{noop}test-only").roles("OWNER").disabled(true).build());
        assertThatThrownBy(() -> oauth.exchange(refresh(client, next.get("refresh_token")))).hasMessage("invalid_grant");
        users.updateUser(User.withUsername("owner").password("{noop}test-only").roles("OWNER").build());
        t.clock.advance(31L * 86400000); assertThatThrownBy(() -> oauth.exchange(refresh(client, next.get("refresh_token")))).hasMessage("invalid_grant");
    }
    @Test void registrationAndAuthorizationRejectUnsafeRedirectsPkceAndScopes() throws Exception {
        for (String value : List.of("https://user@chatgpt.com/callback", "https://chatgpt.com/callback#fragment", "http://evil.example/callback", "javascript:alert(1)", "https://chatgpt.com/\ncallback"))
            assertThatThrownBy(() -> oauth.register("client", List.of(value), null)).hasMessage("invalid_redirect_uri");
        var client = client(); var request = request(client);
        request.put("code_challenge_method", "plain"); assertThatThrownBy(() -> oauth.authorize(request)).hasMessage("invalid_request");
        request.put("code_challenge_method", "S256"); request.put("redirect_uri", CALLBACK + "?unregistered=true"); assertThatThrownBy(() -> oauth.authorize(request)).hasMessage("invalid_redirect_uri");
        request.put("redirect_uri", CALLBACK); request.put("scope", "admin:write"); assertThatThrownBy(() -> oauth.authorize(request)).hasMessage("invalid_scope");
        var valid = oauth.authorize(request(client)); assertThatThrownBy(() -> oauth.approve(pat(List.of("research:read")), valid)).hasMessage("personal_access_token_scope_mismatch");
        t.clock.advance(601000); assertThatThrownBy(() -> oauth.approve(pat(valid.scopes()), valid)).hasMessage("authorization_expired");
    }
    @Test void patToolAndScopeUpdatesImmediatelyAffectExistingOAuthAndShareTheParentRateLimit()throws Exception{
        var client=client();var request=request(client);request.put("scope",String.join(" ",AccessScopes.ALL));
        var parent=pats.create("owner",new PersonalAccessTokens.Create("Dynamic parent",List.of("market:read"),0,20,List.of("get_quote")));
        var issued=oauth.approve(parent.secret(),oauth.authorize(request));var token=oauth.exchange(exchange(client,issued.code()));String access=(String)token.get("access_token");
        assertThat(token.get("scope")).isEqualTo("market:read");var before=oauth.authenticate(access);assertThat(before.allowedTools()).containsExactly("get_quote");
        assertThat(before.rateLimitId()).isEqualTo(parent.token().id());assertThat(before.requestsPerMinute()).isEqualTo(20);
        pats.grants("owner",parent.token().id(),AccessScopes.ALL,List.of("get_quote","get_simulation_account"),true);
        var updated=service().authenticate(access);assertThat(updated.scopes()).containsAll(AccessScopes.ALL);assertThat(updated.allowedTools()).containsExactlyInAnyOrder("get_quote","get_simulation_account");
        oauth.used(updated);assertThat(pats.list("owner").getFirst().useCount()).isEqualTo(1);
        pats.grants("owner",parent.token().id(),AccessScopes.ALL,List.of(),true);assertThat(service().authenticate(access).allowedTools()).isEmpty();
        pats.revoke("owner",parent.token().id());assertThatThrownBy(()->oauth.authenticate(access)).hasMessage("INVALID_ACCESS_TOKEN");
        assertThatThrownBy(()->oauth.exchange(refresh(client,token.get("refresh_token")))).hasMessage("invalid_grant");assertThat(oauth.list("owner").getFirst().state()).isEqualTo("TOKEN_INACTIVE");
    }
    @Test void clientScopeCeilingCannotBeExceededAndOAuthLifetimesNeverExceedTheBackingPat()throws Exception{
        var client=client();var parent=pats.create("owner",new PersonalAccessTokens.Create("Expiring parent",AccessScopes.ALL,1,120));t.clock.advance(86340000);
        var request=request(client);request.put("scope","market:read");var issued=oauth.approve(parent.secret(),oauth.authorize(request));var tokens=oauth.exchange(exchange(client,issued.code()));
        assertThat(tokens.get("expires_in")).isEqualTo(60L);assertThat(oauth.authenticate((String)tokens.get("access_token")).scopes()).containsExactly("market:read");
        assertThat(t.jdbc.queryForList("SELECT expires_at FROM gc_oauth_token")).allSatisfy(row->assertThat(((java.time.OffsetDateTime)row.get("expires_at")).toInstant()).isEqualTo(parent.token().expiresAt()));
        t.clock.advance(61000);assertThatThrownBy(()->oauth.exchange(refresh(client,tokens.get("refresh_token")))).hasMessage("invalid_grant");
    }
    @Test void tokenInspectionIsReadOnlyAndNeverReturnsOrPersistsTheSubmittedSecret()throws Exception{
        var client=client();String secret=pat(List.of("market:read"));var authorization=oauth.authorize(request(client));
        var preview=t.mapper.valueToTree(oauth.inspect(secret,authorization));assertThat(preview.path("tools")).hasSize(5);
        assertThat(preview.toString()).doesNotContain(secret);assertThat(t.jdbc.queryForObject("SELECT count(*) FROM gc_oauth_grant",Integer.class)).isZero();
        assertThatThrownBy(()->oauth.approve("owner",authorization)).hasMessage("invalid_personal_access_token");
        var issued=oauth.approve(secret,authorization);assertThat(t.jdbc.queryForList("SELECT * FROM gc_oauth_grant").toString()+t.jdbc.queryForList("SELECT * FROM gc_event")).doesNotContain(secret);
        assertThat(issued.code()).startsWith("gc_oac_");
    }
    @Test void directPatAndOAuthShareOneQuotaUsingADeterministicClock()throws Exception{
        var parent=pats.create("owner",new PersonalAccessTokens.Create("Shared quota",List.of("market:read"),0,10));var client=client();
        var issued=oauth.approve(parent.secret(),oauth.authorize(request(client)));var tokens=oauth.exchange(exchange(client,issued.code()));
        var filter=new AccessTokenFilter(pats,oauth,t.clock,users);
        for(int i=0;i<11;i++){
            var request=new org.springframework.mock.web.MockHttpServletRequest("GET","/api/mcp");var response=new org.springframework.mock.web.MockHttpServletResponse();
            request.addHeader("Authorization","Bearer "+(i<5?parent.secret():tokens.get("access_token")));
            filter.doFilter(request,response,(req,res)->{});assertThat(response.getStatus()).isEqualTo(i<10?200:429);
        }
        assertThat(pats.active("owner",parent.token().id()).useCount()).isEqualTo(10);
    }
}
