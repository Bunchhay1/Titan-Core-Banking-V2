package com.titan.promotions.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class IdempotencyService {
    
    private static final String KEY_PREFIX = "promo:processed:";
    private static final long TTL_DAYS = 7;
    
    private final RedisTemplate<String, Object> redisTemplate;
    
    public boolean isProcessed(Long transactionId) {
        String key = KEY_PREFIX + transactionId;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }
    
    public boolean markAsProcessed(Long transactionId) {
        String key = KEY_PREFIX + transactionId;
        Boolean success = redisTemplate.opsForValue().setIfAbsent(key, "1", TTL_DAYS, TimeUnit.DAYS);
        
        if (Boolean.TRUE.equals(success)) {
            log.debug("Transaction {} marked as processed", transactionId);
            return true;
        }
        
        log.warn("Transaction {} already processed (duplicate)", transactionId);
        return false;
    }
}
