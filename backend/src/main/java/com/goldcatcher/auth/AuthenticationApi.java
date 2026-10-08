package com.goldcatcher.auth;

import jakarta.servlet.http.*;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthenticationApi {
    private final ProofOfWork pow; private final AuthenticationManager manager;
    private final HttpSessionSecurityContextRepository contexts; private final HttpSessionCsrfTokenRepository csrf;
    public AuthenticationApi(ProofOfWork pow,AuthenticationManager manager,HttpSessionSecurityContextRepository contexts,HttpSessionCsrfTokenRepository csrf) {
        this.pow=pow;this.manager=manager;this.contexts=contexts;this.csrf=csrf;
    }
    @GetMapping("/challenge") public Map<String,Object> challenge(HttpServletRequest request,CsrfToken token) {
        return Map.of("challenge",pow.issue(request.getSession().getId(),request.getRemoteAddr()),"csrfHeader",token.getHeaderName(),"csrfToken",token.getToken());
    }
    public record Login(String username,String password,String challengeId,String solution) {}
    @PostMapping("/login") public ResponseEntity<?> login(@RequestBody Login login,HttpServletRequest request,HttpServletResponse response) {
        var session=request.getSession(false);
        if(session==null)return ResponseEntity.badRequest().body(Map.of("error","POW_REQUIRED"));
        pow.verify(session.getId(),login.challengeId(),login.solution());
        if(login.username()==null||login.password()==null||login.username().length()>120||login.password().length()>256)
            return ResponseEntity.badRequest().body(Map.of("error","LOGIN_INVALID"));
        try {
            var auth=manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(login.username(),login.password()));
            request.changeSessionId();
            new CsrfAuthenticationStrategy(csrf).onAuthentication(auth,request,response);
            var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);contexts.saveContext(context,request,response);
            // Return the masked token exactly as Spring's request handler exposes it, after rotation.
            return ResponseEntity.ok(Map.of("username",auth.getName()));
        } catch(AuthenticationException rejected) {
            pow.failed(request.getRemoteAddr());return ResponseEntity.status(401).body(Map.of("error","LOGIN_FAILED"));
        }
    }
    @ExceptionHandler(ProofOfWork.Rejected.class) public ResponseEntity<?> rejected(ProofOfWork.Rejected failure) {
        return ResponseEntity.status(failure.status).header("Retry-After",failure.status==429?"60":"0").body(Map.of("error",failure.getMessage()));
    }
}
