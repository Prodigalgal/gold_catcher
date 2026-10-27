package com.goldcatcher.auth;

import java.security.Principal;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/access/tokens")
public class AccessTokenApi {
    private final PersonalAccessTokens tokens;
    public AccessTokenApi(PersonalAccessTokens tokens) { this.tokens = tokens; }
    @GetMapping public ResponseEntity<?> list(Principal user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("tokens", tokens.list(user.getName()), "scopes", PersonalAccessTokens.SCOPES,
            "mcpPath", "/api/mcp", "restPath", "/api/external/v1", "maximumActiveTokens", 100));
    }
    @PostMapping public ResponseEntity<?> create(Principal user, @RequestBody PersonalAccessTokens.Create input) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(tokens.create(user.getName(), input));
    }
    @PostMapping("/{id}/revoke") public ResponseEntity<?> revoke(Principal user, @PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tokens.revoke(user.getName(), id));
    }
    @ExceptionHandler(PersonalAccessTokens.Rejected.class) public ResponseEntity<?> rejected(PersonalAccessTokens.Rejected failure) {
        return ResponseEntity.status(failure.status).cacheControl(CacheControl.noStore()).body(Map.of("error", failure.getMessage()));
    }
}
