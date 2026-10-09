package com.titan.titancorebanking.config;

import com.titan.titancorebanking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
@RequiredArgsConstructor
public class ApplicationConfig {

    private final UserRepository userRepository;

    @Bean
    public UserDetailsService userDetailsService() {
        // [MODIFIED] Enhanced exception message to log security context rejections more clearly.
        return username -> userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Security context rejected: User '" + username + "' not found."));
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        // [MODIFIED] Leveraged 'var' for local variable type inference.
        var authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService());
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(final AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        /*
         * STAFF ENGINEER NOTE:
         * The default BCrypt work factor is 10. By elevating this to 12, we exponentially
         * increase the computational cost of brute-force and rainbow table attacks,
         * meeting modern enterprise banking compliance standards for credential storage.
         */
        // [MODIFIED] Increased BCrypt computational cost (strength) from default 10 to 12.
        // ការដំឡើងកម្រិត Work Factor ដល់ 12 ធ្វើឲ្យការវាយប្រហារបែប Brute-force ត្រូវការពេលវេលាយូរជាងមុនរាប់សិបដង ដែលជាស្តង់ដារសុវត្ថិភាពខ្ពស់សម្រាប់ប្រព័ន្ធធនាគារ។
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public RestTemplate restTemplate(final RestTemplateBuilder builder) {
        // [MODIFIED] Replaced default unconfigured RestTemplate with a robust, timeout-bound configuration.
        // ការកំណត់ Timeout យ៉ាងតឹងរ៉ឹងការពារមិនឲ្យប្រព័ន្ធគាំង (Thread Pool Exhaustion) ពេលហៅទៅកាន់ Service ផ្សេងៗ (ឧទាហរណ៍ Loan Service) ហើយ Service នោះយឺត ឬគាំង។
        return builder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
    }
}