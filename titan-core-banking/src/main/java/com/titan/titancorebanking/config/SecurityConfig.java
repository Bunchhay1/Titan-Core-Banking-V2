package com.titan.titancorebanking.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final AuthenticationProvider authenticationProvider;
    private final JwtAuthenticationFilter jwtAuthFilter; // Assuming you have this
    private final AuthenticationEntryPoint restAuthenticationEntryPoint;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // Disable CSRF for APIs
                .authorizeHttpRequests(auth -> auth
                        // ✅ CRITICAL: Whitelist these endpoints so anyone can access them
                        .requestMatchers(
                            "/api/v1/auth/**", 
                            "/test-connection", 
                            "/actuator/health", 
                            "/actuator/prometheus", 
                            "/error",
                            "/swagger-ui/**",
                            "/swagger-ui.html",
                            "/v3/api-docs/**",
                            "/swagger-resources/**",
                            "/webjars/**"
                        ).permitAll()
                        // Internal service-to-service endpoint: notification service fetches
                        // device tokens to fire APNs pushes. Only reachable within the Docker
                        // network — not exposed on the public gateway.
                        .requestMatchers("/api/v1/notifications/internal/**").permitAll()
                        // Internal endpoint called by titan-loans-service to deduct processing fees.
                        // Only reachable within the Docker/K8s internal network.
                        .requestMatchers("/api/v1/transactions/internal/**").permitAll()
                        // Internal endpoint called by titan-loans-service to fetch account info by ID.
                        // Only reachable within the Docker/K8s internal network.
                        .requestMatchers("/api/v1/accounts/{id}").permitAll()
                        // ✅ QR Payment endpoints (/api/v1/qr/**) are intentionally NOT
                        // whitelisted here – they require a valid JWT token.
                        // Lock down everything else
                        .anyRequest().authenticated()
                )
                .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(restAuthenticationEntryPoint))
                .authenticationProvider(authenticationProvider)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
