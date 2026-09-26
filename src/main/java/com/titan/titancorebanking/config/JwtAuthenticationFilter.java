package com.titan.titancorebanking.config;

import com.titan.titancorebanking.service.imple.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;             // ✅ Import Logger
import org.slf4j.LoggerFactory;      // ✅ Import LoggerFactory
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    // ✅ បង្កើត Logger ដើម្បីប្រើប្រាស់ (ឬប្រើ @Slf4j លើ Class ក៏បាន)
    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");
        final String jwt;
        final String userEmail;

        // ✅ 1. ការពារ៖ បើគ្មាន Header ឬមិនមែន Bearer សូមឈប់ត្រឹមនេះ
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // ✅ 2. ចាប់យក Token (កែសម្រួល៖ លុប "String" នៅពីមុខចេញ ដើម្បីកុំឱ្យជាន់គ្នា)
        jwt = authHeader.substring(7);

        // ✅ 3. ការពារបន្ថែម៖ បើ Token ទទេ ឬគ្មានសញ្ញាចុច (.) មិនបាច់ Parse ទេ
        if (jwt.isEmpty() || !jwt.contains(".")) {
            logger.warn("⚠️ Invalid JWT Format received: {}", jwt);
            filterChain.doFilter(request, response);
            return;
        }

        // ✅ 4. (FIXED) ដកយក Username ពី Token (ចំណុចសំខាន់បំផុតដែលបាត់ពីមុន!)
        // បើមិនហៅបន្ទាត់នេះទេ userEmail នឹង null រហូត
        try {
            userEmail = jwtService.extractUsername(jwt);
        } catch (Exception e) {
            logger.error("❌ Failed to extract username from token: {}", e.getMessage());
            filterChain.doFilter(request, response);
            return;
        }

        // ✅ 5. ដំណើរការ Authenticate
        if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            UserDetails userDetails = this.userDetailsService.loadUserByUsername(userEmail);

            if (jwtService.isTokenValid(jwt, userDetails)) {
                // logger.info("✅ Token is VALID for user: {}", userEmail); // អាចបើក Log នេះបើចង់ឃើញ

                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                        userDetails,
                        null,
                        userDetails.getAuthorities()
                );
                authToken.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request)
                );
                SecurityContextHolder.getContext().setAuthentication(authToken);
            } else {
                logger.warn("❌ Token is INVALID or EXPIRED for user: {}", userEmail);
            }
        }
        filterChain.doFilter(request, response);
    }
}