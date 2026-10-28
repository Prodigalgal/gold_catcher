package com.goldcatcher.auth;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

/** Pure protocol checks. No remote metadata fetch, redirect following, or client-supplied issuer. */
public final class OAuthPolicy {
    private OAuthPolicy() {}
    public static final class Failure extends RuntimeException {
        public final int status;
        public Failure(int status, String error) { super(error); this.status = status; }
    }
    public static String issuer(String value) {
        var uri = redirect(value);
        if (!uri.getRawPath().isEmpty() || uri.getRawQuery() != null || value.endsWith("/")) throw new IllegalArgumentException("OAUTH_ISSUER_MUST_BE_ORIGIN");
        return value;
    }
    public static URI redirect(String value) {
        try {
            if (value == null || value.length() > 2048 || value.chars().anyMatch(c -> c < 33 || c == 127)) throw new IllegalArgumentException();
            var uri = URI.create(value);
            boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || !(uri.getScheme().equals("https") || uri.getScheme().equals("http") && loopback)) throw new IllegalArgumentException();
            return uri;
        } catch (RuntimeException invalid) { throw new Failure(400, "invalid_redirect_uri"); }
    }
    public static List<String> scopes(String value, Collection<String> allowed) {
        if (value == null || value.isBlank() || value.length() > 200) throw new Failure(400, "invalid_scope");
        var parts = Arrays.asList(value.split(" ", -1));
        if (parts.isEmpty() || parts.size() > AccessScopes.ALL.size() || new HashSet<>(parts).size() != parts.size() || !allowed.containsAll(parts)) throw new Failure(400, "invalid_scope");
        return PersonalAccessTokens.SCOPES.stream().filter(parts::contains).toList();
    }
    public static void challenge(String method, String value) {
        if (!"S256".equals(method) || value == null || !value.matches("[A-Za-z0-9_-]{43}")) throw new Failure(400, "invalid_request");
    }
    public static boolean verifies(String verifier, String challenge) {
        if (verifier == null || !verifier.matches("[A-Za-z0-9._~-]{43,128}")) return false;
        return equal(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(verifier)), challenge);
    }
    public static boolean equal(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
    public static String hash(String value) { return HexFormat.of().formatHex(bytes(value)); }
    private static byte[] bytes(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA256_UNAVAILABLE"); }
    }
    public static String secret(String prefix, SecureRandom random) {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
