package com.goldcatcher.auth;

import com.goldcatcher.persistence.DocumentStore;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** High entropy bearer credentials. Only the digest is durable; the secret leaves this class once. */
@Service
public class PersonalAccessTokens {
    public static final List<String> SCOPES = List.of("market:read", "information:read", "research:read");
    public record Create(String name, List<String> scopes, Integer expiresInDays, Integer requestsPerMinute) {}
    public record View(String id, String name, String prefix, List<String> scopes, int requestsPerMinute,
                       Instant createdAt, Instant expiresAt, Instant revokedAt, Instant lastUsedAt, long useCount, String state) {}
    public record Created(View token, String secret) { @Override public String toString() { return "Created[token=" + token.id() + ",secret=REDACTED]"; } }
    public record Identity(String id, String owner, Set<String> scopes, int requestsPerMinute) implements Principal {
        public String getName() { return owner; }
        public void require(String scope) { if (!scopes.contains(scope)) throw new Rejected(403, "INSUFFICIENT_SCOPE"); }
    }
    public static final class Rejected extends RuntimeException {
        public final int status;
        public Rejected(int status, String code) { super(code); this.status = status; }
    }
    private record Stored(View view, String owner, String hash) {}
    private static final String COLUMNS = "id,owner_name,token_name,token_hash,scopes,requests_per_minute,created_at,expires_at,revoked_at,last_used_at,use_count";
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final DocumentStore documents;
    private final SecureRandom random = new SecureRandom();

    public PersonalAccessTokens(JdbcTemplate jdbc, Clock clock, DocumentStore documents) {
        this.jdbc = jdbc; this.clock = clock; this.documents = documents;
    }
    public List<View> list(String owner) {
        return jdbc.query("SELECT " + COLUMNS + " FROM gc_access_token WHERE owner_name=? ORDER BY created_at DESC,id DESC",
            (rs, row) -> stored(rs).view(), owner);
    }
    public synchronized Created create(String owner, Create input) {
        if (input == null || input.name() == null || input.name().isBlank() || input.name().strip().length() > 80
            || input.name().chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("INVALID_TOKEN_NAME");
        if (input.scopes() == null || input.scopes().isEmpty() || input.scopes().size() > SCOPES.size()
            || !SCOPES.containsAll(input.scopes()) || new HashSet<>(input.scopes()).size() != input.scopes().size())
            throw new IllegalArgumentException("INVALID_TOKEN_SCOPES");
        int days = input.expiresInDays() == null ? 90 : input.expiresInDays();
        int rate = input.requestsPerMinute() == null ? 120 : input.requestsPerMinute();
        if (days < 1 || days > 365 || rate < 10 || rate > 600) throw new IllegalArgumentException("INVALID_TOKEN_LIMITS");
        var scopes = SCOPES.stream().filter(input.scopes()::contains).toList();
        String id = UUID.randomUUID().toString().replace("-", "");
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String secret = "gc_pat_" + id + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant(), expires = now.plus(Duration.ofDays(days));
        var view = new View(id, input.name().strip(), prefix(id), scopes, rate, now, expires, null, null, 0, "ACTIVE");
        documents.transaction(() -> {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM gc_access_token WHERE owner_name=? AND revoked_at IS NULL AND expires_at>?", Long.class, owner, timestamp(now));
            if (count != null && count >= 100) throw new IllegalStateException("ACTIVE_TOKEN_LIMIT_REACHED");
            jdbc.update("INSERT INTO gc_access_token(id,owner_name,token_name,token_hash,scopes,requests_per_minute,created_at,expires_at,use_count) VALUES(?,?,?,?,?,?,?,?,0)",
                id, owner, view.name(), hash(secret), String.join(" ", scopes), rate, timestamp(now), timestamp(expires));
            documents.event(id, "ACCESS_TOKEN_CREATED", Map.of("owner", owner, "name", view.name(), "scopes", scopes, "expiresAt", expires));
            return null;
        });
        return new Created(view, secret);
    }
    public View revoke(String owner, String id) {
        validId(id);
        return documents.transaction(() -> {
            var row = jdbc.query("SELECT " + COLUMNS + " FROM gc_access_token WHERE owner_name=? AND id=?", (rs, n) -> stored(rs), owner, id)
                .stream().findFirst().orElseThrow(() -> new Rejected(404, "TOKEN_NOT_FOUND"));
            if (jdbc.update("UPDATE gc_access_token SET revoked_at=? WHERE id=? AND owner_name=? AND revoked_at IS NULL", timestamp(clock.instant()), id, owner) == 1)
                documents.event(id, "ACCESS_TOKEN_REVOKED", Map.of("owner", owner, "name", row.view().name()));
            return jdbc.queryForObject("SELECT " + COLUMNS + " FROM gc_access_token WHERE id=?", (rs, n) -> stored(rs).view(), id);
        });
    }
    public Identity authenticate(String secret) {
        if (secret == null || !secret.matches("gc_pat_[a-f0-9]{32}\\.[A-Za-z0-9_-]{43}")) throw new Rejected(401, "INVALID_ACCESS_TOKEN");
        String id = secret.substring(7, 39);
        var row = jdbc.query("SELECT " + COLUMNS + " FROM gc_access_token WHERE id=?", (rs, n) -> stored(rs), id).stream().findFirst().orElse(null);
        if (row == null || !MessageDigest.isEqual(hash(secret).getBytes(StandardCharsets.US_ASCII), row.hash().getBytes(StandardCharsets.US_ASCII))
            || !row.view().state().equals("ACTIVE")) throw new Rejected(401, "INVALID_ACCESS_TOKEN");
        return new Identity(id, row.owner(), Set.copyOf(row.view().scopes()), row.view().requestsPerMinute());
    }
    public void used(Identity identity) {
        var now = timestamp(clock.instant());
        if (jdbc.update("UPDATE gc_access_token SET last_used_at=?,use_count=use_count+1 WHERE id=? AND revoked_at IS NULL AND expires_at>?", now, identity.id(), now) != 1)
            throw new Rejected(401, "INVALID_ACCESS_TOKEN");
    }
    private Stored stored(ResultSet rs) throws SQLException {
        Instant revoked = instant(rs, "revoked_at"), expires = instant(rs, "expires_at");
        String id = rs.getString("id");
        var view = new View(id, rs.getString("token_name"), prefix(id), List.of(rs.getString("scopes").split(" ")),
            rs.getInt("requests_per_minute"), instant(rs, "created_at"), expires, revoked, instant(rs, "last_used_at"), rs.getLong("use_count"),
            revoked != null ? "REVOKED" : !expires.isAfter(clock.instant()) ? "EXPIRED" : "ACTIVE");
        return new Stored(view, rs.getString("owner_name"), rs.getString("token_hash"));
    }
    private static String prefix(String id) { return "gc_pat_" + id.substring(0, 8) + "…"; }
    private static Timestamp timestamp(Instant at) { return Timestamp.from(at); }
    private static Instant instant(ResultSet rs, String field) throws SQLException { var t = rs.getTimestamp(field); return t == null ? null : t.toInstant(); }
    private static void validId(String id) { if (id == null || !id.matches("[a-f0-9]{32}")) throw new Rejected(404, "TOKEN_NOT_FOUND"); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("TOKEN_HASH_UNAVAILABLE", impossible); }
    }
}
