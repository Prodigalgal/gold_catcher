package com.goldcatcher.auth;

import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.*;
import static com.goldcatcher.auth.OAuthPolicy.*;

/** Public protocol endpoints are separate from the Session + CSRF consent and management endpoints. */
@RestController
public class OAuthApi {
    private static final String PENDING = OAuthApi.class.getName() + ".pending";
    private final OAuthService oauth;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    public OAuthApi(OAuthService oauth, ObjectMapper mapper, Clock clock) { this.oauth = oauth; this.mapper = mapper; this.clock = clock; }
    @GetMapping({"/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/api/mcp"})
    public ResponseEntity<?> resourceMetadata() { return ok(oauth.resourceMetadata()); }
    @GetMapping("/.well-known/oauth-authorization-server") public ResponseEntity<?> serverMetadata() { return ok(oauth.serverMetadata()); }
    @PostMapping(value="/api/oauth/register", consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> register(HttpServletRequest request) throws IOException {
        JsonNode body;
        try { body = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body(request)); }
        catch (RuntimeException invalid) { throw new Failure(400, "invalid_client_metadata"); }
        if (body == null || !body.isObject() || !body.path("redirect_uris").isArray()) throw new Failure(400, "invalid_client_metadata");
        if (body.has("token_endpoint_auth_method") && !body.path("token_endpoint_auth_method").asText().equals("none")) throw new Failure(400, "invalid_client_metadata");
        checkArray(body, "grant_types", Set.of("authorization_code", "refresh_token"));
        checkArray(body, "response_types", Set.of("code"));
        List<String> redirects = new ArrayList<>();
        for (var value : body.path("redirect_uris")) { if (!value.isTextual()) throw new Failure(400, "invalid_redirect_uri"); redirects.add(value.asText()); }
        if (body.has("client_name") && !body.path("client_name").isTextual() || body.has("scope") && !body.path("scope").isTextual()) throw new Failure(400, "invalid_client_metadata");
        var client = oauth.register(body.path("client_name").asText("MCP client"), redirects, body.has("scope") ? body.path("scope").asText() : null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(Map.of("client_id", client.id(), "client_id_issued_at", client.createdAt().getEpochSecond(),
            "client_name", client.name(), "redirect_uris", client.redirectUris(), "scope", String.join(" ", client.scopes()),
            "token_endpoint_auth_method", "none", "grant_types", List.of("authorization_code", "refresh_token"), "response_types", List.of("code")));
    }
    @GetMapping("/api/oauth/authorize") public ResponseEntity<?> authorize(HttpServletRequest request) {
        String query = request.getQueryString();
        if (query == null || query.length() > 8192) throw new Failure(400, "invalid_request");
        var authorization = oauth.authorize(parameters(query));
        var session = request.getSession(); String ticket = secret("", random);
        synchronized (session) {
            var pending = requests(session);
            pending.values().removeIf(value -> !value.expiresAt().isAfter(clock.instant()));
            if (pending.size() >= 8) throw new Failure(429, "too_many_authorization_requests");
            pending.put(ticket, authorization);
        }
        return ResponseEntity.status(303).cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer")
            .location(URI.create("/oauth/consent?request=" + ticket)).build();
    }
    @GetMapping("/api/oauth/consent") public ResponseEntity<?> consent(@RequestParam String request, HttpServletRequest servlet) {
        var pending = pending(servlet, request);
        return ok(Map.of("clientName", pending.clientName(), "redirectUri", pending.redirectUri(), "scopes", pending.scopes(),
            "resource", oauth.resource(), "expiresAt", pending.expiresAt(), "authorizationDays", 30,
            "authenticated", servlet.isUserInRole("OWNER")));
    }
    public record Consent(String request, String decision, List<String> scopes) {}
    @PostMapping("/api/oauth/consent") public ResponseEntity<?> consent(@RequestBody Consent consent, HttpServletRequest servlet, Principal user) {
        if (consent == null || consent.decision() == null || !Set.of("approve", "deny").contains(consent.decision())) throw new Failure(400, "invalid_request");
        var session = servlet.getSession(false);
        if (session == null || user == null) throw new Failure(401, "authentication_required");
        synchronized (session) {
            var pending = pending(servlet, consent.request());
            var result = new LinkedHashMap<String,String>(); result.put("iss", oauth.issuer());
            if (pending.state() != null) result.put("state", pending.state());
            if (consent.decision().equals("approve")) result.put("code", oauth.approve(user.getName(), pending, consent.scopes()).code());
            else result.put("error", "access_denied");
            requests(session).remove(consent.request());
            String separator = pending.redirectUri().contains("?") ? "&" : "?";
            return ok(Map.of("redirectUrl", pending.redirectUri() + separator + encoded(result)));
        }
    }
    @PostMapping(value="/api/oauth/token", consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<?> token(HttpServletRequest request) throws IOException {
        if (request.getHeader("Authorization") != null) throw new Failure(401, "invalid_client");
        return ok(oauth.exchange(parameters(new String(body(request), StandardCharsets.UTF_8))));
    }
    @PostMapping(value="/api/oauth/revoke", consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<?> revokeToken(HttpServletRequest request) throws IOException {
        if (request.getHeader("Authorization") != null) throw new Failure(401, "invalid_client");
        var parameters = parameters(new String(body(request), StandardCharsets.UTF_8));
        if (!parameters.containsKey("token")) throw new Failure(400, "invalid_request");
        oauth.revokeToken(parameters.get("client_id"), parameters.get("token")); return ok(Map.of());
    }
    @GetMapping("/api/access/oauth") public ResponseEntity<?> grants(Principal owner) {
        return ok(Map.of("authorizations", oauth.list(owner.getName()), "issuer", oauth.issuer(), "mcpUrl", oauth.resource(), "registration", "DCR_PUBLIC_PKCE_S256"));
    }
    @PostMapping("/api/access/oauth/{id}/revoke") public ResponseEntity<?> revokeGrant(@PathVariable String id, Principal owner) {
        oauth.revoke(owner.getName(), id); return ok(Map.of("status", "REVOKED"));
    }
    @ExceptionHandler(Failure.class) public ResponseEntity<?> rejected(Failure failure) {
        var response = ResponseEntity.status(failure.status).cacheControl(CacheControl.noStore());
        if (failure.status == 429) response.header("Retry-After", "60");
        if (failure.status == 401) response.header("WWW-Authenticate", oauth.challenge());
        return response.body(Map.of("error", failure.getMessage()));
    }
    private byte[] body(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > 16384) throw new Failure(413, "request_too_large");
        byte[] bytes = request.getInputStream().readNBytes(16385);
        if (bytes.length > 16384) throw new Failure(413, "request_too_large");
        return bytes;
    }
    private OAuthService.Authorization pending(HttpServletRequest request, String ticket) {
        var session = request.getSession(false);
        if (session == null || ticket == null || !ticket.matches("[A-Za-z0-9_-]{43}")) throw new Failure(400, "authorization_expired");
        synchronized (session) {
            var value = requests(session).get(ticket);
            if (value == null || !value.expiresAt().isAfter(clock.instant())) { requests(session).remove(ticket); throw new Failure(400, "authorization_expired"); }
            return value;
        }
    }
    @SuppressWarnings("unchecked") private Map<String,OAuthService.Authorization> requests(HttpSession session) {
        var value = (Map<String,OAuthService.Authorization>)session.getAttribute(PENDING);
        if (value == null) { value = new LinkedHashMap<>(); session.setAttribute(PENDING, value); }
        return value;
    }
    private static Map<String,String> parameters(String text) {
        var result = new LinkedHashMap<String,String>();
        try {
            if (text.isBlank()) throw new IllegalArgumentException();
            for (String pair : text.split("&", -1)) {
                String[] pieces = pair.split("=", 2);
                if (pieces.length != 2) throw new IllegalArgumentException();
                String key = URLDecoder.decode(pieces[0], StandardCharsets.UTF_8), value = URLDecoder.decode(pieces[1], StandardCharsets.UTF_8);
                if (key.isBlank() || result.putIfAbsent(key, value) != null) throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) { throw new Failure(400, "invalid_request"); }
        return result;
    }
    private static String encoded(Map<String,String> values) {
        return values.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
    }
    private static void checkArray(JsonNode body, String field, Set<String> allowed) {
        if (!body.has(field)) return;
        if (!body.path(field).isArray() || body.path(field).isEmpty() || body.path(field).size() > allowed.size()) throw new Failure(400, "invalid_client_metadata");
        var seen = new HashSet<String>();
        for (var value : body.path(field)) if (!value.isTextual() || !allowed.contains(value.asText()) || !seen.add(value.asText())) throw new Failure(400, "invalid_client_metadata");
    }
    private static ResponseEntity<?> ok(Object value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(value); }
}
