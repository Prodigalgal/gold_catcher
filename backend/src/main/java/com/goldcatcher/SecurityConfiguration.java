package com.goldcatcher;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

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

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                .anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults())
            .build();
    }
}
