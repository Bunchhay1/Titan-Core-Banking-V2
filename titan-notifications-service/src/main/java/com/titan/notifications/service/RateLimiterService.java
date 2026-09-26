package com.titan.notifications.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class RateLimiterService {
    
    private final RedisTemplate<String, Object> redisTemplate;
    private static final int SMS_LIMIT = 3;
    private static final int EMAIL_LIMIT = 10;
    private static final Duration WINDOW = Duration.ofMinutes(1);
    
    public boolean allowSms(String userId) {
        return checkLimit("sms:" + userId, SMS_LIMIT);
    }
    
    public boolean allowEmail(String userId) {
        return checkLimit("email:" + userId, EMAIL_LIMIT);
    }
    
    private boolean checkLimit(String key, int maxRequests) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == null) return false;
        
        if (count == 1) {
            redisTemplate.expire(key, WINDOW.getSeconds(), TimeUnit.SECONDS);
        }
        
        boolean allowed = count <= maxRequests;
        if (!allowed) {
            log.warn("🚫 Rate limit exceeded for key: {} (count: {})", key, count);
        }
        return allowed;
    }
}
