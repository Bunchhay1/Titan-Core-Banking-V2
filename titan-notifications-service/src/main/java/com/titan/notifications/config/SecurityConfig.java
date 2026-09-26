package com.titan.notifications.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration for titan-notifications-service.
 *
 * This service is an INTERNAL backend service — it is called by:
 *   - Kafka consumer (no HTTP auth)
 *   - titan-core-banking (service-to-service)
 *   - iOS app (via Render public URL, reads audit/preferences)
 *
 * Strategy: permit all HTTP endpoints (no JWT required).
 * The service is protected at the network level by Render's private service
 * routing. If you need auth in future, add JWT filter here.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF — stateless REST API, no browser sessions
            .csrf(AbstractHttpConfigurer::disable)

            // Stateless — no session cookies
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // Permit all endpoints — internal service
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/api/**").permitAll()
                .anyRequest().permitAll()
            );

        return http.build();
    }
}
