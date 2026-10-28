package com.goldcatcher.auth;

import java.security.Principal;
import java.util.Map;
import com.goldcatcher.external.ExternalTools;
import tools.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/access/tokens")
public class AccessTokenApi {
    private final PersonalAccessTokens tokens;
    public AccessTokenApi(PersonalAccessTokens tokens) { this.tokens = tokens; }
    @GetMapping public ResponseEntity<?> list(Principal user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("tokens", tokens.list(user.getName()), "scopes", PersonalAccessTokens.SCOPES,
            "mcpPath", "/api/mcp", "restPath", "/api/external/v1", "maximumActiveTokens", 100,"tools",ExternalTools.definitions().stream().map(t->Map.of("name",t.name(),"title",t.title(),"scope",t.scope(),"readOnly",t.readOnly())).toList()));
    }
    @PostMapping public ResponseEntity<?> create(Principal user, @RequestBody PersonalAccessTokens.Create input) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(tokens.create(user.getName(), input));
    }
    @PostMapping("/{id}/revoke") public ResponseEntity<?> revoke(Principal user, @PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tokens.revoke(user.getName(), id));
    }
    @PostMapping("/{id}/permanent") public ResponseEntity<?> permanent(Principal user, @PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tokens.permanent(user.getName(), id));
    }
    @PostMapping("/{id}/scopes") public ResponseEntity<?> scopes(Principal user,@PathVariable String id,@RequestBody JsonNode input){
        if(!input.isObject()||input.propertyNames().stream().anyMatch(k->!java.util.Set.of("scopes","allowedTools").contains(k)))throw new IllegalArgumentException("INVALID_TOKEN_SCOPES");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tokens.grants(user.getName(),id,strings(input.path("scopes")),input.hasNonNull("allowedTools")?strings(input.get("allowedTools")):null,input.has("allowedTools")));
    }
    private static java.util.List<String> strings(JsonNode node){if(!node.isArray()||node.size()>200)throw new IllegalArgumentException("INVALID_TOKEN_SCOPES");var values=new java.util.ArrayList<String>();for(var value:node){if(!value.isTextual())throw new IllegalArgumentException("INVALID_TOKEN_SCOPES");values.add(value.asText());}return values;}
    @ExceptionHandler(PersonalAccessTokens.Rejected.class) public ResponseEntity<?> rejected(PersonalAccessTokens.Rejected failure) {
        return ResponseEntity.status(failure.status).cacheControl(CacheControl.noStore()).body(Map.of("error", failure.getMessage()));
    }
}
