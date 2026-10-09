package com.titan.titancorebanking.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final AuthenticationProvider authenticationProvider;
    private final AuthenticationEntryPoint restAuthenticationEntryPoint;

    // [MODIFIED] Extracted public endpoints into a static constant array for clean configuration management.
    // ប្រមូលផ្តុំ Public Endpoints ទៅជា Array តែមួយ (Constant) ដើម្បីងាយស្រួលអាន និងជៀសវាងការសរសេរកូដរញ៉េរញ៉ៃនៅក្នុង Security Filter Chain។
    private static final String[] PUBLIC_WHITELIST = {
            "/api/v1/auth/**",
            "/api/v1/notifications/internal/**",
            "/api/v1/transactions/internal/**",
            "/actuator/health",
            "/actuator/prometheus",
            "/error",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/swagger-resources/**",
            "/webjars/**",
            "/test-connection"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
                // [MODIFIED] Disabled CSRF intentionally for stateless APIs, explicitly defined.
                // បិទ CSRF ដោយចេតនា ព្រោះប្រព័ន្ធ API របស់យើងប្រើប្រាស់ JWT (Stateless Token) ដែលមិនពឹងផ្អែកលើ Cookie Session នាំឱ្យគ្មានហានិភ័យពីការវាយប្រហារប្រភេទ CSRF នោះទេ។
                .csrf(AbstractHttpConfigurer::disable)

                // [MODIFIED] Hardened HTTP response headers to prevent clickjacking attacks (FrameOptions: DENY).
                // បន្ថែមការកំណត់ Security Headers (FrameOptions = DENY) ដើម្បីការពារការវាយប្រហារបែប Clickjacking (ការលួចបង្កប់ Web យើងក្នុង iFrame របស់ Hacker)។
                .headers(headers -> headers
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                )

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_WHITELIST).permitAll()

                        // [MODIFIED] Explicitly listed critical resource paths before the fallback.
                        .requestMatchers("/api/v1/accounts/**").authenticated()
                        .requestMatchers("/api/v1/qr/**").authenticated()

                        // Fallback: Everything else MUST be authenticated
                        .anyRequest().authenticated()
                )

                // [MODIFIED] Strictly enforced stateless session management to prevent HttpSession memory leaks.
                // ធានាថា Spring Security មិនបង្កើត Session (HttpSession) ទុកក្នុង RAM របស់ Server ឡើយ។
                .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Proper Exception Handling for 401 Unauthorized
                .exceptionHandling(ex -> ex.authenticationEntryPoint(restAuthenticationEntryPoint))

                .authenticationProvider(authenticationProvider)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}