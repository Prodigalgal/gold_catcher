package com.goldcatcher.external;

import com.goldcatcher.auth.PersonalAccessTokens;
import com.goldcatcher.auth.PersonalAccessTokens.Identity;
import com.goldcatcher.auth.OAuthService;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;

/** GET never mutates. POST executes one individually declared, scoped operation. */
@RestController
@RequestMapping("/api/external/v1")
public class ExternalDataApi {
    private final ExternalTools tools;
    private final ObjectMapper mapper;
    private final OAuthService oauth;
    public ExternalDataApi(ExternalTools tools, ObjectMapper mapper, OAuthService oauth) { this.tools = tools; this.mapper = mapper; this.oauth = oauth; }
    @GetMapping public Object catalog(@AuthenticationPrincipal Identity identity) {
        return Map.of("readOnly", tools.available(identity).stream().allMatch(ExternalTools.Tool::readOnly), "tools", tools.available(identity).stream().map(ExternalTools.Tool::definition).toList(), "endpointPattern", "/api/external/v1/{toolName}","commandEndpoint","/api/external/v1");
    }
    @GetMapping("/{name}") public Object query(@AuthenticationPrincipal Identity identity, @PathVariable String name, @RequestParam Map<String,String> parameters) {
        var tool = tools.catalog().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow(() -> new IllegalArgumentException("UNKNOWN_TOOL"));
        identity.require(tool.scope());
        identity.requireTool(name);
        if(!tool.readOnly())return ResponseEntity.status(405).allow(HttpMethod.POST).body(Map.of("error","WRITE_REQUIRES_POST","endpoint","/api/external/v1"));
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
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> command(@AuthenticationPrincipal Identity identity,HttpServletRequest request)throws IOException{
        if(request.getContentLengthLong()>32768)return ResponseEntity.status(413).body(Map.of("error","TOOL_REQUEST_TOO_LARGE"));
        byte[] bytes=request.getInputStream().readNBytes(32769);if(bytes.length>32768)return ResponseEntity.status(413).body(Map.of("error","TOOL_REQUEST_TOO_LARGE"));
        tools.jackson.databind.JsonNode body;try{body=mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);}catch(RuntimeException invalid){throw new IllegalArgumentException("INVALID_TOOL_CALL_JSON");}
        if(body==null||!body.isObject()||!body.path("name").isTextual()||body.size()>2)throw new IllegalArgumentException("INVALID_TOOL_CALL");
        Object result=tools.call(identity,body.path("name").asText(),body.has("arguments")?body.get("arguments"):mapper.createObjectNode());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
    @ExceptionHandler(ExternalCommands.Rejected.class) public ResponseEntity<?> commandRejected(ExternalCommands.Rejected failure){return ResponseEntity.status(failure.status).body(Map.of("error",failure.getMessage(),"operationId",failure.operationId));}
    @ExceptionHandler(PersonalAccessTokens.Rejected.class) public ResponseEntity<?> rejected(PersonalAccessTokens.Rejected failure) {
        var builder = ResponseEntity.status(failure.status);
        if (failure.requiredScope != null) builder.header("WWW-Authenticate", oauth.insufficientScope(failure.requiredScope));
        return builder.body(Map.of("error", failure.getMessage()));
    }
}
