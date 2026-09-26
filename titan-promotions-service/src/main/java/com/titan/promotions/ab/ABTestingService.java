package com.titan.promotions.ab;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

@Service
@Slf4j
@RequiredArgsConstructor
public class ABTestingService {
    
    private final RedisTemplate<String, String> redisTemplate;
    
    public ABVariant assignVariant(Long accountId, Long campaignId) {
        String key = "ab:" + campaignId + ":" + accountId;
        String cached = redisTemplate.opsForValue().get(key);
        
        if (cached != null) {
            return ABVariant.valueOf(cached);
        }
        
        // 50/50 split
        ABVariant variant = ThreadLocalRandom.current().nextBoolean() ? ABVariant.A : ABVariant.B;
        redisTemplate.opsForValue().set(key, variant.name());
        
        return variant;
    }
    
    public BigDecimal calculateReward(ABVariant variant, BigDecimal transactionAmount) {
        return switch (variant) {
            case A -> new BigDecimal("5.00"); // Flat $5
            case B -> transactionAmount.multiply(new BigDecimal("0.02")); // 2% cashback
        };
    }
    
    public void recordMetric(ABVariant variant, String metricType, BigDecimal value) {
        String key = "ab:metrics:" + variant + ":" + metricType;
        redisTemplate.opsForValue().increment(key, value.doubleValue());
        log.info("AB Test metric: variant={}, metric={}, value={}", variant, metricType, value);
    }
    
    public enum ABVariant { A, B }
}
