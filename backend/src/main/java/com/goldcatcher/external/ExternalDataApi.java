package com.goldcatcher.external;

import com.goldcatcher.auth.PersonalAccessTokens;
import com.goldcatcher.auth.PersonalAccessTokens.Identity;
import com.goldcatcher.auth.OAuthService;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** GET-only JSON alternative for clients without MCP. Tool argument contracts are identical. */
@RestController
@RequestMapping("/api/external/v1")
public class ExternalDataApi {
    private final ExternalTools tools;
    private final ObjectMapper mapper;
    private final OAuthService oauth;
    public ExternalDataApi(ExternalTools tools, ObjectMapper mapper, OAuthService oauth) { this.tools = tools; this.mapper = mapper; this.oauth = oauth; }
    @GetMapping public Object catalog(@AuthenticationPrincipal Identity identity) {
        return Map.of("readOnly", true, "tools", tools.available(identity).stream().map(ExternalTools.Tool::definition).toList(), "endpointPattern", "/api/external/v1/{toolName}");
    }
    @GetMapping("/{name}") public Object query(@AuthenticationPrincipal Identity identity, @PathVariable String name, @RequestParam Map<String,String> parameters) {
        var tool = tools.catalog().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow(() -> new IllegalArgumentException("UNKNOWN_TOOL"));
        var args = mapper.createObjectNode();
        @SuppressWarnings("unchecked") var fields = (Map<String,Map<String,Object>>) tool.inputSchema().get("properties");
        parameters.forEach((key, value) -> {
            String type = String.valueOf(fields.getOrDefault(key, Map.of()).get("type"));
            if (type.equals("integer")) {
                try { args.put(key, Integer.parseInt(value)); } catch (NumberFormatException invalid) { throw new IllegalArgumentException("INVALID_INTEGER_ARGUMENT"); }
            } else if (type.equals("boolean")) {
                if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("INVALID_BOOLEAN_ARGUMENT"); args.put(key, Boolean.parseBoolean(value));
            } else args.put(key, value);
        });
        return tools.call(identity, name, args);
    }
    @ExceptionHandler(PersonalAccessTokens.Rejected.class) public ResponseEntity<?> rejected(PersonalAccessTokens.Rejected failure) {
        var builder = ResponseEntity.status(failure.status);
        if (failure.requiredScope != null) builder.header("WWW-Authenticate", oauth.insufficientScope(failure.requiredScope));
        return builder.body(Map.of("error", failure.getMessage()));
    }
}
