package com.titan.titancorebanking.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**") // អនុញ្ញាតគ្រប់ផ្លូវ (API)
                        .allowedOrigins("http://localhost:3000", "http://localhost:4200", "*") // អនុញ្ញាត Frontend
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS") // អនុញ្ញាតគ្រប់វិធី
                        .allowedHeaders("*")
                        .allowCredentials(false); // បើដាក់ true ត្រូវកំណត់ Origin ជាក់លាក់
            }
        };
    }
}