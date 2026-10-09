package com.titan.titancorebanking.config;

import com.titan.titancorebanking.service.imple.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(
            @NonNull final HttpServletRequest request,
            @NonNull final HttpServletResponse response,
            @NonNull final FilterChain filterChain
    ) throws ServletException, IOException {

        // [MODIFIED] Use Spring's robust HttpHeaders constant rather than a hardcoded string.
        final String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        // [MODIFIED] Fast-fail: Optimized string checks and used constant for prefix.
        // ការត្រួតពិនិត្យ Header ដោយប្រើ StringUtils.hasText ដើរលឿនជាង និងការពារ NullPointerException មុននឹងបន្តការងារផ្សេងៗ។
        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        final String jwt = authHeader.substring(BEARER_PREFIX.length());
        final String username;

        try {
            // [MODIFIED] Corrected variable naming (userEmail -> username) to reflect system domain accuracy.
            username = jwtService.extractUsername(jwt);
        } catch (Exception e) {
            // [MODIFIED] Mitigated Log Forging/Spoofing attacks by reducing severity to DEBUG and omitting raw token dumps.
            // បន្ថយការ Log ពី Error មកត្រឹម Debug និងមិនបង្ហាញ Token ទាំងស្រុង ដើម្បីការពារកុំឲ្យ Hacker បំពេញ (Flood) Server Logs និងបង្ការការបែកធ្លាយទិន្នន័យសម្ងាត់ (Log Injection/Spoofing)។
            log.debug("JWT extraction failed or malformed token provided: {}", e.getMessage());
            filterChain.doFilter(request, response);
            return;
        }

        // [MODIFIED] Strict security context check to ensure no overwriting of existing authentications.
        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {

            var userDetails = this.userDetailsService.loadUserByUsername(username);

            if (jwtService.isTokenValid(jwt, userDetails)) {
                var authToken = new UsernamePasswordAuthenticationToken(
                        userDetails,
                        null,
                        userDetails.getAuthorities()
                );

                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);

                log.debug("Successfully authenticated user: {}", username);
            } else {
                log.debug("Token validation failed for user: {}", username);
            }
        }

        filterChain.doFilter(request, response);
    }
}