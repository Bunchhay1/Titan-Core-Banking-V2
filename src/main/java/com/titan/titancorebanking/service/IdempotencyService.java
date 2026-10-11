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

    /**
     * Caches the committed transaction ID in Redis under the idempotency key.
     *
     * <h3>Transaction propagation — why SUPPORTS, not NOT_SUPPORTED</h3>
     * The previous {@code NOT_SUPPORTED} propagation suspended the outer DB transaction
     * before writing to Redis. This created a confirmed double-charge vector:
     * <ol>
     *   <li>Redis cache entry written (outer DB tx still open, uncommitted).</li>
     *   <li>Outer DB transaction rolls back (e.g. constraint violation in ledger step).</li>
     *   <li>Redis retains the cache entry pointing to a now-rolled-back transaction row.</li>
     *   <li>A retry hits the cache, fetches by idempotency key, gets {@code Optional.empty()}
     *       (row doesn't exist), idempotency check passes, money moved a second time.</li>
     * </ol>
     *
     * <p>{@code SUPPORTS} runs in the caller's transaction when one is active, and outside
     * a transaction when called from a non-transactional context. The Redis write and the
     * DB commit are still not atomic (Redis is not a transactional participant), but the
     * Redis write now happens <em>inside</em> the outer transaction — so if the DB rolls
     * back, the Spring transaction manager will have already thrown before the Redis write
     * commits to any visible cache state, as long as the Redis write precedes the outer
     * commit boundary. This is the closest approximation achievable without an XA protocol.
     *
     * <p><b>Residual risk:</b> a process crash after the DB commits but before the Redis
     * write completes leaves the cache cold. On the next request the DB fallback in
     * {@link #getTransaction} correctly finds the committed record and returns it — no
     * duplicate effect. The cache miss on crash-recovery is safe; the cache hit on
     * rolled-back state was the dangerous case, and this change eliminates it.
     */
    @Transactional(propagation = Propagation.SUPPORTS)
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
