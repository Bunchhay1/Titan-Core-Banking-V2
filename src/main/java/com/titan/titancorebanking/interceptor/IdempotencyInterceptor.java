package com.titan.titancorebanking.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class IdempotencyInterceptor extends OncePerRequestFilter {
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public IdempotencyInterceptor(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod())
                || !request.getRequestURI().startsWith("/api/v1/transactions");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String idempotencyKey = request.getHeader(IDEMPOTENCY_HEADER);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
        // Force body read to cache it for downstream and generate hash
        byte[] requestBody = wrappedRequest.getInputStream().readAllBytes();
        String payloadHash = generateHash(requestBody);

        String cacheKey = buildCacheKey(wrappedRequest, idempotencyKey, payloadHash);

        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                response.setStatus(HttpServletResponse.SC_CONFLICT);
                response.setContentType("application/json");
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.getWriter().write("{\"error\":\"Duplicate request detected\",\"message\":\"Request with this Idempotency-Key has already been processed\"}");
                return;
            }
        } catch (Exception e) {
            logger.warn("Redis unavailable in IdempotencyInterceptor, skipping cache check: " + e.getMessage());
            filterChain.doFilter(wrappedRequest, response);
            return;
        }

        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        filterChain.doFilter(wrappedRequest, wrappedResponse);

        int status = wrappedResponse.getStatus();
        if (status >= 200 && status < 300) {
            try {
                byte[] body = wrappedResponse.getContentAsByteArray();
                CachedResponse payload = new CachedResponse(status, wrappedResponse.getContentType(),
                        new String(body, StandardCharsets.UTF_8));
                redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(payload), IDEMPOTENCY_TTL);
            } catch (Exception e) {
                logger.warn("Redis unavailable, skipping idempotency cache write: " + e.getMessage());
            }
        }
        wrappedResponse.copyBodyToResponse();
    }

    private String buildCacheKey(HttpServletRequest request, String idempotencyKey, String payloadHash) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String principal = authentication != null && authentication.isAuthenticated()
                ? authentication.getName()
                : "anonymous";
        return String.format("idempotency:%s:%s:%s:%s", principal, request.getRequestURI(), idempotencyKey, payloadHash);
    }

    private String generateHash(byte[] payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    private record CachedResponse(int status, String contentType, String body) {}
}