package com.titan.promotions.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class DistributedLockService {
    
    private static final String LOCK_PREFIX = "campaign:lock:";
    private static final long WAIT_TIME_SECONDS = 5;
    private static final long LEASE_TIME_SECONDS = 10;
    
    private final RedissonClient redissonClient;
    
    public <T> T executeWithLock(Long campaignId, LockCallback<T> callback) {
        String lockKey = LOCK_PREFIX + campaignId;
        RLock lock = redissonClient.getLock(lockKey);
        
        try {
            if (lock.tryLock(WAIT_TIME_SECONDS, LEASE_TIME_SECONDS, TimeUnit.SECONDS)) {
                try {
                    return callback.execute();
                } finally {
                    lock.unlock();
                }
            } else {
                log.warn("Failed to acquire lock for campaign {}", campaignId);
                throw new RuntimeException("Could not acquire distributed lock");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Lock acquisition interrupted", e);
        }
    }
    
    @FunctionalInterface
    public interface LockCallback<T> {
        T execute();
    }
}
