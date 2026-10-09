package com.titan.titancorebanking.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@Slf4j
public class CorsConfig {

    /*
     * STAFF ENGINEER NOTE:
     * Never hardcode frontend URLs (like localhost:3000) into the compiled Java code.
     * This makes the application rigid and requires a code change to deploy to a new environment.
     * We externalize this to a configuration property with a safe local default.
     */
    // [MODIFIED] Externalized allowed origins for 12-Factor App compliance.
    // ទាញយក URLs ដែលអនុញ្ញាត (Allowed Origins) ពី File Configuration ជំនួសឲ្យការសរសេរកូដជាប់ (Hardcode) ដើម្បីងាយស្រួលប្តូរពេលដាក់លើ Production Server ពិតប្រាកដ។
    @Value("${cors.allowed-origins:http://localhost:3000,http://localhost:4200}")
    private String[] allowedOrigins;

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(@NonNull final CorsRegistry registry) {
                log.info("Initializing CORS configuration with origins: {}", (Object) allowedOrigins);

                // [MODIFIED] Restricted global "/**" to explicit "/api/**" path mapping.
                registry.addMapping("/api/**")

                        // [MODIFIED] Removed the catastrophic wildcard "*" origin risk.
                        // លុបចោលសញ្ញា "*" ដែលអនុញ្ញាតឲ្យ Web ទាំងអស់អាចហៅ API នេះបាន។ ការបើក "*" គឺជាចន្លោះប្រហោងសុវត្ថិភាពដ៏ធ្ងន់ធ្ងរ (CORS Vulnerability) សម្រាប់ប្រព័ន្ធធនាគារ។
                        .allowedOrigins(allowedOrigins)

                        // [MODIFIED] Explicitly declared safe HTTP methods.
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")

                        // [MODIFIED] Locked down wildcard headers to only accept specifically required enterprise headers.
                        // បិទមិនឲ្យ Client បញ្ជូន Header អ្វីក៏បានចូលមក Server ឡើយ ដោយយើងកំណត់ត្រឹមតែ Header ដែលចាំបាច់ (ដូចជា Authorization និង Idempotency-Key)។
                        .allowedHeaders("Authorization", "Content-Type", "Idempotency-Key", "X-Requested-With", "Accept")

                        // [MODIFIED] Enabled credentials for strict origin compliance and future HTTP-Only cookie auth.
                        .allowCredentials(true)

                        // [MODIFIED] Added Pre-flight caching to reduce network latency.
                        // កំណត់ឲ្យ Browser ចងចាំការត្រួតពិនិត្យ CORS (Pre-flight OPTIONS request) រយៈពេល ១ម៉ោង ដើម្បីកាត់បន្ថយការហៅទៅ Server ផ្ទួនៗ (កាត់បន្ថយ Network Latency)។
                        .maxAge(3600);
            }
        };
    }
}