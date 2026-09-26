package com.titan.titancorebanking.service;

import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {
    
    private final TransactionRepository transactionRepository;
    private final StringRedisTemplate redisTemplate;
    
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    
    /**
     * Check if a transaction with this idempotency key already exists.
     * Uses Redis cache for fast lookups, falls back to database.
     * 
     * ✅ FIX: Cache key format now matches IdempotencyInterceptor
     * Format: idempotency:{principal}:{uri}:{key}
     */
    public Optional<Transaction> getTransaction(String idempotencyKey, String uri) {
        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            return Optional.empty();
        }

        // Try Redis cache first, fall back to DB if Redis is down
        try {
            String cacheKey = buildCacheKey(uri, idempotencyKey);
            String cachedTransactionId = redisTemplate.opsForValue().get(cacheKey);
            if (cachedTransactionId != null) {
                log.debug("✅ Idempotency cache hit: {}", idempotencyKey);
                return transactionRepository.findByIdempotencyKey(idempotencyKey);
            }
        } catch (Exception e) {
            log.warn("⚠️ Redis unavailable for idempotency check, using DB fallback: {}", e.getMessage());
        }

        return transactionRepository.findByIdempotencyKey(idempotencyKey);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void cacheTransaction(String idempotencyKey, String uri, Transaction transaction) {
        if (idempotencyKey == null || idempotencyKey.isEmpty()) return;
        try {
            String cacheKey = buildCacheKey(uri, idempotencyKey);
            redisTemplate.opsForValue().set(cacheKey, transaction.getId().toString(), IDEMPOTENCY_TTL);
            log.debug("💾 Cached transaction with idempotency key: {}", idempotencyKey);
        } catch (Exception e) {
            log.warn("⚠️ Redis unavailable, skipping idempotency cache: {}", e.getMessage());
        }
    }
    
    /**
     * Build cache key matching IdempotencyInterceptor format.
     * Format: idempotency:{principal}:{uri}:{key}
     */
    private String buildCacheKey(String uri, String idempotencyKey) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String principal = authentication != null && authentication.isAuthenticated()
                ? authentication.getName()
                : "anonymous";
        return String.format("idempotency:%s:%s:%s", principal, uri, idempotencyKey);
    }
}
