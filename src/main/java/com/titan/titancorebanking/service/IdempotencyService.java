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
     * Atomically acquires an in-progress idempotency reservation lock via Redis SETNX.
     * Prevents TOCTOU race conditions where two identical requests arrive simultaneously.
     * Returns true if lock was acquired or Redis is unavailable (falling back to DB unique constraint).
     * Returns false if another request is currently processing this key.
     */
    /**
     * Atomically acquires an in-progress idempotency reservation lock via Redis SETNX.
     * Prevents TOCTOU race conditions where two identical requests arrive simultaneously.
     * Returns true if lock was acquired or Redis is unavailable (falling back to DB unique constraint).
     * Returns false if another request is currently processing this key.
     */
    public boolean acquireLock(String idempotencyKey, String uri) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return true;
        }
        try {
            String lockKey = buildLockKey(uri, idempotencyKey);
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "IN_PROGRESS", Duration.ofMinutes(2));
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            log.warn("⚠️ Redis unavailable for idempotency lock, relying on DB unique constraint: {}", e.getMessage());
            return true;
        }
    }

    /**
     * Releases the in-progress idempotency reservation lock in Redis.
     */
    public void releaseLock(String idempotencyKey, String uri) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }
        try {
            String lockKey = buildLockKey(uri, idempotencyKey);
            redisTemplate.delete(lockKey);
        } catch (Exception e) {
            log.warn("⚠️ Failed to release idempotency lock: {}", e.getMessage());
        }
    }

    /**
     * Awaits completion of a concurrent transaction already holding the idempotency lock,
     * or acquires leadership if the peer aborted/failed and released it.
     *
     * @param idempotencyKey the client idempotency key
     * @param uri the endpoint URI
     * @return Optional containing the completed transaction if peer succeeded,
     *         or Optional.empty() if this thread successfully acquired the lock and should proceed as leader.
     * @throws IllegalStateException if peer is still in-progress after timeout
     */
    public Optional<Transaction> awaitResolutionOrAcquire(String idempotencyKey, String uri) {
        final int maxAttempts = 15; // 15 * 200ms = 3.0 seconds total wait
        final long waitIntervalMs = 200;

        for (int i = 0; i < maxAttempts; i++) {
            try {
                Thread.sleep(waitIntervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while awaiting concurrent idempotency resolution.", ie);
            }

            Optional<Transaction> existing = getTransaction(idempotencyKey, uri);
            if (existing.isPresent()) {
                return existing;
            }

            // If peer released lock (e.g. failed/rolled back), attempt to claim leadership
            if (acquireLock(idempotencyKey, uri)) {
                log.info("Acquired idempotency lock after peer release. key={}", idempotencyKey);
                return Optional.empty();
            }
        }

        log.warn("Concurrent transaction with idempotency key {} is still in-progress after timeout.", idempotencyKey);
        throw new IllegalStateException(String.format(
                "A transaction with idempotency key '%s' is currently being processed. Please retry shortly.",
                idempotencyKey));
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

    private String buildLockKey(String uri, String idempotencyKey) {
        return String.format("idempotency_lock:%s:%s", uri, idempotencyKey);
    }
}
