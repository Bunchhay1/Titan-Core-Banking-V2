package com.titan.titancorebanking.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

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
        // Only apply idempotency logic to POST requests on transaction endpoints
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

        String cacheKey = buildCacheKey(request, idempotencyKey);

        try {
            String cachedPayload = redisTemplate.opsForValue().get(cacheKey);
            if (cachedPayload != null) {
                // If a cached response exists, return 409 Conflict immediately to prevent duplicate processing
                response.setStatus(HttpServletResponse.SC_CONFLICT);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());

                String errorJson = objectMapper.writeValueAsString(
                        java.util.Map.of(
                                "error", "Duplicate Request Detected",
                                "message", "A request with this Idempotency-Key has already been successfully processed.",
                                "status", HttpServletResponse.SC_CONFLICT
                        )
                );
                response.getWriter().write(errorJson);
                return;
            }
        } catch (Exception e) {
            // Fail-open strategy: If Redis is unavailable, log the warning and allow the request to proceed.
            // The secondary DB-level idempotency constraint will act as the ultimate fallback.
            logger.warn("Redis unavailable in IdempotencyInterceptor, bypassing cache check: " + e.getMessage());
        }

        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        filterChain.doFilter(request, wrappedResponse);

        int status = wrappedResponse.getStatus();

        // Only cache successful requests (2xx status codes)
        if (status >= 200 && status < 300) {
            try {
                byte[] body = wrappedResponse.getContentAsByteArray();
                CachedResponse payload = new CachedResponse(
                        status,
                        wrappedResponse.getContentType(),
                        new String(body, StandardCharsets.UTF_8)
                );
                redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(payload), IDEMPOTENCY_TTL);
            } catch (Exception e) {
                logger.warn("Redis unavailable, skipping idempotency cache write: " + e.getMessage());
            }
        }

        wrappedResponse.copyBodyToResponse();
    }

    private String buildCacheKey(HttpServletRequest request, String idempotencyKey) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String principal = authentication != null && authentication.isAuthenticated()
                ? authentication.getName()
                : "anonymous";
        return String.format("idempotency:%s:%s:%s", principal, request.getRequestURI(), idempotencyKey);
    }

    private record CachedResponse(int status, String contentType, String body) {}
}