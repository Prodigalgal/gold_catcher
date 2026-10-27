package com.goldcatcher;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import com.goldcatcher.auth.AccessTokenFilter;
import com.goldcatcher.auth.PersonalAccessTokens;
import java.time.Clock;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
public class SecurityConfiguration {
    @Bean
    UserDetailsService users(
        @Value("${gold.auth.username}") String username,
        @Value("${gold.auth.password}") String password
    ) {
        // Without a configured credential there is no usable account.
        boolean configured = !username.isBlank() && !password.isBlank();
        String effectivePassword = configured ? password : UUID.randomUUID().toString();
        return new InMemoryUserDetailsManager(User.withUsername(username.isBlank() ? "disabled" : username)
            .password("{bcrypt}" + new BCryptPasswordEncoder().encode(effectivePassword))
            .roles("OWNER").disabled(!configured).build());
    }

    @Bean PasswordEncoder passwordEncoder() { return PasswordEncoderFactories.createDelegatingPasswordEncoder(); }
    @Bean AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(users); provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }
    @Bean HttpSessionSecurityContextRepository contextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean HttpSessionCsrfTokenRepository csrfRepository() { return new HttpSessionCsrfTokenRepository(); }

    @Bean @Order(1)
    SecurityFilterChain externalSecurity(HttpSecurity http, PersonalAccessTokens tokens, Clock clock, UserDetailsService users) throws Exception {
        return http.securityMatcher("/api/mcp", "/api/mcp/**", "/api/external/**")
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .securityContext(context -> context.securityContextRepository(new org.springframework.security.web.context.NullSecurityContextRepository()))
            .csrf(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable).requestCache(cache -> cache.requestCache(new NullRequestCache()))
            .addFilterBefore(new AccessTokenFilter(tokens, clock, users), AnonymousAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("EXTERNAL_READER"))
            .build();
    }

    @Bean @Order(2)
    SecurityFilterChain security(HttpSecurity http, HttpSessionSecurityContextRepository contexts, HttpSessionCsrfTokenRepository csrf) throws Exception {
        return http.authorizeHttpRequests(auth -> auth
                // Initial SSE requests are authenticated below. Only finish an already admitted stream after logout/disconnect.
                .requestMatchers(request -> request.getDispatcherType()==jakarta.servlet.DispatcherType.ASYNC
                    &&request.getMethod().equals("GET")&&request.getRequestURI().equals(request.getContextPath()+"/api/events")).permitAll()
                .requestMatchers("/actuator/health/liveness", "/actuator/health/readiness", "/api/auth/challenge", "/api/auth/login").permitAll()
                .requestMatchers("/api/access/**").hasRole("OWNER")
                .anyRequest().authenticated())
            .httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
            .securityContext(context -> context.securityContextRepository(contexts).requireExplicitSave(true))
            .csrf(config -> config.csrfTokenRepository(csrf))
            .requestCache(cache -> cache.requestCache(new NullRequestCache()))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request,response,error) -> { response.setStatus(401); response.setContentType("application/json"); response.getWriter().write("{\"error\":\"AUTH_REQUIRED\"}"); })
                .accessDeniedHandler((request,response,error) -> { response.setStatus(403); response.setContentType("application/json"); response.getWriter().write("{\"error\":\"CSRF_OR_ACCESS_DENIED\"}"); }))
            .logout(logout -> logout.logoutUrl("/api/auth/logout").deleteCookies("JSESSIONID")
                .logoutSuccessHandler((request,response,auth) -> { response.setContentType("application/json"); response.getWriter().write("{\"status\":\"LOGGED_OUT\"}"); }))
            .build();
    }
}
