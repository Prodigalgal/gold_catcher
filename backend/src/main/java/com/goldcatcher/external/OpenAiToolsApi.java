package com.goldcatcher.external;

import com.goldcatcher.auth.PersonalAccessTokens;
import com.goldcatcher.auth.PersonalAccessTokens.Identity;
import com.goldcatcher.auth.OAuthService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.*;

/** OpenAI function-calling adapter with the same scoped operation contracts as MCP. */
@RestController
@RequestMapping("/api/external/v1/openai")
public class OpenAiToolsApi {
    private final ExternalTools tools;
    private final ObjectMapper mapper;
    private final OAuthService oauth;
    public OpenAiToolsApi(ExternalTools tools, ObjectMapper mapper, OAuthService oauth) { this.tools = tools; this.mapper = mapper; this.oauth = oauth; }
    @GetMapping("/tools") public Object definitions(@AuthenticationPrincipal Identity identity, @RequestParam(defaultValue="chat") String format) {
        if (!List.of("chat", "responses").contains(format)) throw new IllegalArgumentException("INVALID_OPENAI_FORMAT");
        return Map.of("format", format, "tools", tools.available(identity).stream().map(tool -> {
            var definition = Map.of("name", tool.name(), "description", tool.description(), "parameters", tool.inputSchema(), "strict", false);
            if (format.equals("chat")) return (Object) Map.of("type", "function", "function", definition);
            var flat = new LinkedHashMap<String,Object>(); flat.put("type", "function"); flat.putAll(definition); return flat;
        }).toList());
    }
    @PostMapping(value="/tool", consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> execute(@AuthenticationPrincipal Identity identity, HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > 32768) return rejected(413, "TOOL_REQUEST_TOO_LARGE");
        byte[] bytes = request.getInputStream().readNBytes(32769);
        if (bytes.length > 32768) return rejected(413, "TOOL_REQUEST_TOO_LARGE");
        JsonNode body;
        try { body = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes); }
        catch (RuntimeException malformed) { return rejected(400, "INVALID_TOOL_CALL_JSON"); }
        if (body == null || !body.isObject()) return rejected(400, "INVALID_TOOL_CALL");
        // Accept exactly one complete, non-streaming call. Client assembles deltas before submission.
        var call = body.path("tool_call"); String type = call.path("type").asText();
        boolean chat = type.equals("function"), responses = type.equals("function_call");
        if (!chat && !responses) return rejected(400, "INVALID_TOOL_CALL_TYPE");
        String callId = call.path(chat ? "id" : "call_id").asText();
        var function = chat ? call.path("function") : call;
        if (!callId.matches("[A-Za-z0-9_-]{1,200}") || !function.path("name").isTextual() || !function.path("arguments").isTextual())
            return rejected(400, "INVALID_TOOL_CALL");
        JsonNode args;
        try { args = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(function.path("arguments").asText()); }
        catch (RuntimeException malformed) { return rejected(400, "INVALID_TOOL_ARGUMENTS_JSON"); }
        Object data = tools.call(identity, function.path("name").asText(), args);
        String output = mapper.writeValueAsString(data);
        var result = new LinkedHashMap<String,Object>();
        result.put("message", Map.of("role", "tool", "tool_call_id", callId, "content", output));
        result.put("responseOutput", Map.of("type", "function_call_output", "call_id", callId, "output", output));
        result.put("isError", data instanceof ExternalCommands.Result command&&!command.ok()); return ResponseEntity.ok(result);
    }
    private ResponseEntity<?> rejected(int status, String code) { return ResponseEntity.status(status).body(Map.of("error", code)); }
    @ExceptionHandler(ExternalCommands.Rejected.class) public ResponseEntity<?> commandRejected(ExternalCommands.Rejected failure){return ResponseEntity.status(failure.status).body(Map.of("error",failure.getMessage(),"operationId",failure.operationId));}
    @ExceptionHandler(PersonalAccessTokens.Rejected.class) public ResponseEntity<?> rejected(PersonalAccessTokens.Rejected failure) {
        var builder = ResponseEntity.status(failure.status);
        if (failure.requiredScope != null) builder.header("WWW-Authenticate", oauth.insufficientScope(failure.requiredScope));
        return builder.body(Map.of("error", failure.getMessage()));
    }
}
