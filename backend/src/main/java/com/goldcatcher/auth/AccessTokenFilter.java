package com.goldcatcher.auth;

import com.github.benmanes.caffeine.cache.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.*;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Only installed in the stateless external chain, never accepted by owner/session APIs. */
public final class AccessTokenFilter extends OncePerRequestFilter {
    private static final class Window { long minute; int count; final Semaphore concurrent = new Semaphore(2); }
    private final Cache<String, Window> windows = Caffeine.newBuilder().maximumSize(10000).expireAfterAccess(Duration.ofMinutes(3)).build();
    private final Semaphore capacity = new Semaphore(6);
    private final PersonalAccessTokens tokens;
    private final OAuthService oauth;
    private final Clock clock;
    private final UserDetailsService users;
    public AccessTokenFilter(PersonalAccessTokens tokens, OAuthService oauth, Clock clock, UserDetailsService users) { this.tokens = tokens; this.oauth = oauth; this.clock = clock; this.users = users; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Vary", "Authorization, Origin");
        Window window = null; boolean acquired = false, global = false;
        try {
            String header = request.getHeader("Authorization");
            if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) throw new PersonalAccessTokens.Rejected(401, "ACCESS_TOKEN_REQUIRED");
            String bearer = header.substring(7);
            boolean oauthAccess = bearer.startsWith("gc_oat_");
            var identity = oauthAccess ? oauth.authenticate(bearer) : tokens.authenticate(bearer);
            try {
                var owner = users.loadUserByUsername(identity.owner());
                if (!owner.isEnabled() || !owner.isAccountNonLocked() || !owner.isAccountNonExpired()
                    || owner.getAuthorities().stream().noneMatch(role -> role.getAuthority().equals("ROLE_OWNER"))) throw new PersonalAccessTokens.Rejected(401, "INVALID_ACCESS_TOKEN");
            } catch (UsernameNotFoundException missing) { throw new PersonalAccessTokens.Rejected(401, "INVALID_ACCESS_TOKEN"); }
            window = windows.get(identity.id(), key -> new Window());
            long minute = clock.instant().getEpochSecond() / 60;
            synchronized (window) {
                if (window.minute != minute) { window.minute = minute; window.count = 0; }
                if (++window.count > identity.requestsPerMinute()) throw new PersonalAccessTokens.Rejected(429, "TOKEN_RATE_LIMITED");
            }
            response.setHeader("X-RateLimit-Limit", String.valueOf(identity.requestsPerMinute()));
            acquired = window.concurrent.tryAcquire(); global = acquired && capacity.tryAcquire();
            if (!global) throw new PersonalAccessTokens.Rejected(429, "EXTERNAL_CAPACITY_BUSY");
            if (oauthAccess) oauth.used(identity); else tokens.used(identity);
            var authentication = UsernamePasswordAuthenticationToken.authenticated(identity, null, List.of(new SimpleGrantedAuthority("ROLE_EXTERNAL_READER")));
            var context = SecurityContextHolder.createEmptyContext(); context.setAuthentication(authentication); SecurityContextHolder.setContext(context);
            chain.doFilter(request, response);
        } catch (PersonalAccessTokens.Rejected failure) {
            response.setStatus(failure.status); response.setContentType("application/json");
            if (failure.status == 401) response.setHeader("WWW-Authenticate", oauth.challenge());
            if (failure.requiredScope != null) response.setHeader("WWW-Authenticate", oauth.insufficientScope(failure.requiredScope));
            if (failure.status == 429) response.setHeader("Retry-After", failure.getMessage().equals("TOKEN_RATE_LIMITED") ? String.valueOf(60 - clock.instant().getEpochSecond() % 60) : "5");
            response.getWriter().write("{\"error\":\"" + failure.getMessage() + "\"}");
        } finally {
            if (global) capacity.release(); if (acquired) window.concurrent.release(); SecurityContextHolder.clearContext();
        }
    }
}
