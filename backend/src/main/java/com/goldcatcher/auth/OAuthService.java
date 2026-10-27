package com.goldcatcher.auth;

import com.goldcatcher.persistence.DocumentStore;
import java.security.SecureRandom;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import static com.goldcatcher.auth.OAuthPolicy.*;

/** Authorization code + S256, opaque access tokens, and one-time rotating refresh tokens. */
@Service
public class OAuthService {
    public record Client(String id, String name, List<String> redirectUris, List<String> scopes, Instant createdAt) {}
    public record Authorization(String clientId, String clientName, String redirectUri, List<String> scopes,
                                String challenge, String state, Instant expiresAt) {}
    public record Issued(String code, String grantId) { @Override public String toString() { return "Issued[REDACTED]"; } }
    public record GrantView(String id, String clientName, String redirectUri, List<String> scopes, Instant createdAt,
                            Instant expiresAt, Instant revokedAt, Instant lastUsedAt, long useCount, String state) {}
    private record Grant(String id, String owner, String clientId, String redirectUri, String resource, List<String> scopes,
                         Instant createdAt, Instant expiresAt, Instant revokedAt, Instant lastUsedAt, long useCount) {}
    private record Rotation(Map<String,Object> tokens, boolean rejected) {}
    private final JdbcTemplate jdbc;
    private final DocumentStore documents;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final UserDetailsService users;
    private final SecureRandom random = new SecureRandom();
    private final String issuer;
    private long registrationMinute = -1;
    private int registrations;
    public OAuthService(JdbcTemplate jdbc, DocumentStore documents, ObjectMapper mapper, Clock clock, UserDetailsService users,
                        @Value("${gold.oauth.issuer:https://gold.mnnu.eu.org}") String issuer) {
        this.jdbc = jdbc; this.documents = documents; this.mapper = mapper; this.clock = clock; this.users = users;
        this.issuer = OAuthPolicy.issuer(issuer);
    }
    public String issuer() { return issuer; }
    public String resource() { return issuer + "/api/mcp"; }
    public String challenge() { return "Bearer resource_metadata=\"" + issuer + "/.well-known/oauth-protected-resource/api/mcp\", scope=\"" + String.join(" ", PersonalAccessTokens.SCOPES) + "\""; }
    public String insufficientScope(String scope) {
        if (!PersonalAccessTokens.SCOPES.contains(scope)) throw new IllegalArgumentException("INVALID_SCOPE_CHALLENGE");
        return "Bearer error=\"insufficient_scope\", resource_metadata=\"" + issuer + "/.well-known/oauth-protected-resource/api/mcp\", scope=\"" + scope + "\"";
    }
    public Map<String,Object> resourceMetadata() {
        return Map.of("resource", resource(), "resource_name", "Gold Catcher", "authorization_servers", List.of(issuer),
            "scopes_supported", PersonalAccessTokens.SCOPES, "bearer_methods_supported", List.of("header"));
    }
    public Map<String,Object> serverMetadata() {
        return Map.ofEntries(Map.entry("issuer", issuer), Map.entry("authorization_endpoint", issuer + "/api/oauth/authorize"),
            Map.entry("token_endpoint", issuer + "/api/oauth/token"), Map.entry("registration_endpoint", issuer + "/api/oauth/register"),
            Map.entry("revocation_endpoint", issuer + "/api/oauth/revoke"), Map.entry("response_types_supported", List.of("code")),
            Map.entry("grant_types_supported", List.of("authorization_code", "refresh_token")),
            Map.entry("token_endpoint_auth_methods_supported", List.of("none")), Map.entry("revocation_endpoint_auth_methods_supported", List.of("none")),
            Map.entry("code_challenge_methods_supported", List.of("S256")), Map.entry("scopes_supported", PersonalAccessTokens.SCOPES),
            Map.entry("authorization_response_iss_parameter_supported", true));
    }
    public synchronized Client register(String name, List<String> redirects, String requestedScopes) {
        long minute = clock.instant().getEpochSecond() / 60;
        if (registrationMinute != minute) { registrationMinute = minute; registrations = 0; }
        if (++registrations > 20) throw new Failure(429, "registration_rate_limited");
        if (name == null || name.isBlank() || name.length() > 80 || name.chars().anyMatch(Character::isISOControl)) throw new Failure(400, "invalid_client_metadata");
        if (redirects == null || redirects.isEmpty() || redirects.size() > 5 || new HashSet<>(redirects).size() != redirects.size()) throw new Failure(400, "invalid_redirect_uri");
        redirects.forEach(OAuthPolicy::redirect);
        var allowed = requestedScopes == null ? PersonalAccessTokens.SCOPES : scopes(requestedScopes, PersonalAccessTokens.SCOPES);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM gc_oauth_client", Long.class);
        if (count != null && count >= 10000) throw new Failure(503, "registration_capacity_reached");
        String id = "gc_client_" + UUID.randomUUID().toString().replace("-", "");
        var client = new Client(id, name.strip(), List.copyOf(redirects), allowed, clock.instant());
        jdbc.update("INSERT INTO gc_oauth_client(id,client_name,redirect_uris,scopes,created_at) VALUES(?,?,?,?,?)",
            id, client.name(), mapper.writeValueAsString(client.redirectUris()), String.join(" ", allowed), timestamp(client.createdAt()));
        return client;
    }
    public Client client(String id) {
        if (id == null || !id.matches("gc_client_[a-f0-9]{32}")) throw new Failure(400, "invalid_client");
        return jdbc.query("SELECT * FROM gc_oauth_client WHERE id=?", (rs,n) -> new Client(rs.getString("id"), rs.getString("client_name"),
            mapper.readValue(rs.getString("redirect_uris"), new TypeReference<List<String>>() {}), List.of(rs.getString("scopes").split(" ")), at(rs,"created_at")), id)
            .stream().findFirst().orElseThrow(() -> new Failure(400, "invalid_client"));
    }
    public Authorization authorize(Map<String,String> parameters) {
        var client = client(parameters.get("client_id"));
        String redirect = parameters.get("redirect_uri");
        if (!client.redirectUris().contains(redirect)) throw new Failure(400, "invalid_redirect_uri");
        if (!"code".equals(parameters.get("response_type"))) throw new Failure(400, "unsupported_response_type");
        if (parameters.containsKey("response_mode") && !"query".equals(parameters.get("response_mode"))) throw new Failure(400, "invalid_request");
        resource(parameters.get("resource"));
        OAuthPolicy.challenge(parameters.get("code_challenge_method"), parameters.get("code_challenge"));
        String state = parameters.get("state");
        if (state != null && (state.length() > 2048 || state.chars().anyMatch(Character::isISOControl))) throw new Failure(400, "invalid_request");
        return new Authorization(client.id(), client.name(), redirect, scopes(parameters.get("scope"), client.scopes()),
            parameters.get("code_challenge"), state, clock.instant().plusSeconds(600));
    }
    public synchronized Issued approve(String owner, Authorization request, List<String> selected) {
        requireOwner(owner);
        if (!request.expiresAt().isAfter(clock.instant())) throw new Failure(400, "authorization_expired");
        var scopes = scopes(selected == null ? null : String.join(" ", selected), request.scopes());
        client(request.clientId());
        return documents.transaction(() -> {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM gc_oauth_grant WHERE owner_name=? AND revoked_at IS NULL AND expires_at>?", Long.class, owner, timestamp(clock.instant()));
            if (count != null && count >= 100) throw new Failure(400, "authorization_limit_reached");
            String id = UUID.randomUUID().toString().replace("-", ""), code = secret("gc_oac_", random);
            Instant now = clock.instant(), expires = now.plus(Duration.ofDays(30));
            jdbc.update("INSERT INTO gc_oauth_grant(id,owner_name,client_id,redirect_uri,resource_uri,scopes,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?)",
                id, owner, request.clientId(), request.redirectUri(), resource(), String.join(" ", scopes), timestamp(now), timestamp(expires));
            jdbc.update("INSERT INTO gc_oauth_code(code_hash,grant_id,challenge,expires_at) VALUES(?,?,?,?)", hash(code), id, request.challenge(), timestamp(now.plusSeconds(120)));
            documents.event(id, "OAUTH_AUTHORIZED", Map.of("owner", owner, "clientId", request.clientId(), "scopes", scopes, "expiresAt", expires));
            return new Issued(code, id);
        });
    }
    public Map<String,Object> exchange(Map<String,String> parameters) {
        var client = client(parameters.get("client_id"));
        resource(parameters.get("resource"));
        if (parameters.containsKey("client_secret")) throw new Failure(400, "invalid_client");
        return switch (parameters.getOrDefault("grant_type", "")) {
            case "authorization_code" -> exchangeCode(client, parameters);
            case "refresh_token" -> refresh(client, parameters);
            default -> throw new Failure(400, "unsupported_grant_type");
        };
    }
    private Map<String,Object> exchangeCode(Client client, Map<String,String> parameters) {
        String code = parameters.get("code");
        if (code == null || !code.matches("gc_oac_[A-Za-z0-9_-]{43}")) throw new Failure(400, "invalid_grant");
        return documents.transaction(() -> {
            var codes = jdbc.queryForList("SELECT * FROM gc_oauth_code WHERE code_hash=? FOR UPDATE", hash(code));
            if (codes.isEmpty()) throw new Failure(400, "invalid_grant");
            var row = codes.getFirst(); var grant = grant((String)row.get("grant_id"));
            if (row.get("consumed_at") != null || !instant(row.get("expires_at")).isAfter(clock.instant()) || !active(grant)
                || !grant.clientId().equals(client.id()) || !grant.redirectUri().equals(parameters.get("redirect_uri"))
                || !verifies(parameters.get("code_verifier"), (String)row.get("challenge"))) throw new Failure(400, "invalid_grant");
            requireOwner(grant.owner());
            jdbc.update("UPDATE gc_oauth_code SET consumed_at=? WHERE code_hash=?", timestamp(clock.instant()), hash(code));
            return mint(grant, grant.scopes());
        });
    }
    private Map<String,Object> refresh(Client client, Map<String,String> parameters) {
        String token = parameters.get("refresh_token");
        if (token == null || !token.matches("gc_ort_[A-Za-z0-9_-]{43}")) throw new Failure(400, "invalid_grant");
        Rotation rotation = documents.transaction(() -> {
            var rows = jdbc.queryForList("SELECT * FROM gc_oauth_token WHERE token_hash=? AND token_type='REFRESH' FOR UPDATE", hash(token));
            if (rows.isEmpty()) return new Rotation(null, true);
            var row = rows.getFirst(); var grant = grant((String)row.get("grant_id"));
            if (!grant.clientId().equals(client.id()) || !active(grant) || !instant(row.get("expires_at")).isAfter(clock.instant())) return new Rotation(null, true);
            if (row.get("consumed_at") != null) {
                // Commit revocation before reporting replay; throwing here would roll it back.
                revokeGrant(grant, "OAUTH_REFRESH_REPLAY"); return new Rotation(null, true);
            }
            var previous = List.of(((String)row.get("scopes")).split(" "));
            var scopes = parameters.containsKey("scope") ? scopes(parameters.get("scope"), previous) : previous;
            requireOwner(grant.owner());
            jdbc.update("UPDATE gc_oauth_token SET consumed_at=? WHERE token_hash=?", timestamp(clock.instant()), hash(token));
            return new Rotation(mint(grant, scopes), false);
        });
        if (rotation.rejected()) throw new Failure(400, "invalid_grant");
        return rotation.tokens();
    }
    private Map<String,Object> mint(Grant grant, List<String> scopes) {
        String access = secret("gc_oat_", random), refresh = secret("gc_ort_", random);
        Instant accessExpiry = clock.instant().plusSeconds(3600);
        if (accessExpiry.isAfter(grant.expiresAt())) accessExpiry = grant.expiresAt();
        for (var item : List.of(new Object[]{hash(access), "ACCESS", timestamp(accessExpiry)}, new Object[]{hash(refresh), "REFRESH", timestamp(grant.expiresAt())}))
            jdbc.update("INSERT INTO gc_oauth_token(token_hash,grant_id,token_type,scopes,expires_at) VALUES(?,?,?,?,?)", item[0], grant.id(), item[1], String.join(" ", scopes), item[2]);
        return Map.of("access_token", access, "token_type", "Bearer", "expires_in", Duration.between(clock.instant(), accessExpiry).toSeconds(),
            "refresh_token", refresh, "scope", String.join(" ", scopes));
    }
    public PersonalAccessTokens.Identity authenticate(String access) {
        if (access == null || !access.matches("gc_oat_[A-Za-z0-9_-]{43}")) throw new PersonalAccessTokens.Rejected(401, "INVALID_ACCESS_TOKEN");
        var rows = jdbc.queryForList("SELECT grant_id,scopes FROM gc_oauth_token WHERE token_hash=? AND token_type='ACCESS' AND expires_at>?", hash(access), timestamp(clock.instant()));
        if (rows.isEmpty()) throw new PersonalAccessTokens.Rejected(401, "INVALID_ACCESS_TOKEN");
        var row = rows.getFirst(); var grant = grant((String)row.get("grant_id"));
        if (!active(grant)) throw new PersonalAccessTokens.Rejected(401, "INVALID_ACCESS_TOKEN");
        return new PersonalAccessTokens.Identity(grant.id(), grant.owner(), Set.of(((String)row.get("scopes")).split(" ")), 120);
    }
    public void used(PersonalAccessTokens.Identity identity) {
        if (jdbc.update("UPDATE gc_oauth_grant SET last_used_at=?,use_count=use_count+1 WHERE id=? AND revoked_at IS NULL AND expires_at>? AND resource_uri=?",
            timestamp(clock.instant()), identity.id(), timestamp(clock.instant()), resource()) != 1) throw new PersonalAccessTokens.Rejected(401, "INVALID_ACCESS_TOKEN");
    }
    public void revokeToken(String clientId, String token) {
        client(clientId);
        if (token == null || token.length() > 256) return;
        documents.transaction(() -> {
            var ids = jdbc.queryForList("SELECT grant_id FROM gc_oauth_token WHERE token_hash=?", String.class, hash(token));
            if (!ids.isEmpty()) { var grant = grant(ids.getFirst()); if (grant.clientId().equals(clientId)) revokeGrant(grant, "OAUTH_REVOKED"); }
            return null;
        });
    }
    public List<GrantView> list(String owner) {
        return jdbc.query("SELECT g.*,c.client_name FROM gc_oauth_grant g JOIN gc_oauth_client c ON c.id=g.client_id WHERE g.owner_name=? ORDER BY g.created_at DESC,g.id DESC LIMIT 500",
            (rs,n) -> { var grant = stored(rs); return view(grant, rs.getString("client_name")); }, owner);
    }
    public void revoke(String owner, String id) {
        if (id == null || !id.matches("[a-f0-9]{32}")) throw new Failure(404, "authorization_not_found");
        documents.transaction(() -> {
            var grants = jdbc.query("SELECT * FROM gc_oauth_grant WHERE id=? AND owner_name=? FOR UPDATE", (rs,n) -> stored(rs), id, owner);
            if (grants.isEmpty()) throw new Failure(404, "authorization_not_found");
            revokeGrant(grants.getFirst(), "OAUTH_REVOKED"); return null;
        });
    }
    private void revokeGrant(Grant grant, String event) {
        if (jdbc.update("UPDATE gc_oauth_grant SET revoked_at=? WHERE id=? AND revoked_at IS NULL", timestamp(clock.instant()), grant.id()) == 1)
            documents.event(grant.id(), event, Map.of("owner", grant.owner(), "clientId", grant.clientId()));
    }
    private Grant grant(String id) { return jdbc.queryForObject("SELECT * FROM gc_oauth_grant WHERE id=?", (rs,n) -> stored(rs), id); }
    private Grant stored(ResultSet rs) throws SQLException {
        return new Grant(rs.getString("id"), rs.getString("owner_name"), rs.getString("client_id"), rs.getString("redirect_uri"), rs.getString("resource_uri"),
            List.of(rs.getString("scopes").split(" ")), at(rs,"created_at"), at(rs,"expires_at"), at(rs,"revoked_at"), at(rs,"last_used_at"), rs.getLong("use_count"));
    }
    private GrantView view(Grant grant, String name) { return new GrantView(grant.id(), name, grant.redirectUri(), grant.scopes(), grant.createdAt(), grant.expiresAt(), grant.revokedAt(), grant.lastUsedAt(), grant.useCount(), grant.revokedAt() != null ? "REVOKED" : active(grant) ? "ACTIVE" : "EXPIRED"); }
    private boolean active(Grant grant) { return grant.revokedAt() == null && grant.expiresAt().isAfter(clock.instant()) && resource().equals(grant.resource()); }
    private void resource(String value) { if (!resource().equals(value)) throw new Failure(400, "invalid_target"); }
    private void requireOwner(String name) {
        try { var user = users.loadUserByUsername(name); if (!user.isEnabled() || !user.isAccountNonLocked() || !user.isAccountNonExpired()
            || user.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_OWNER"))) throw new IllegalArgumentException(); }
        catch (RuntimeException rejected) { throw new Failure(400, "invalid_grant"); }
    }
    private static Timestamp timestamp(Instant at) { return Timestamp.from(at); }
    private static Instant at(ResultSet rs, String name) throws SQLException { var value = rs.getTimestamp(name); return value == null ? null : value.toInstant(); }
    private static Instant instant(Object value) { return value instanceof java.time.OffsetDateTime time ? time.toInstant() : ((Timestamp)value).toInstant(); }
}
