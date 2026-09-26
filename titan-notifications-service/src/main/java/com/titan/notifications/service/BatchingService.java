package com.titan.notifications.service;

import com.titan.notifications.strategy.ProviderStrategyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class BatchingService {
    
    private final RedisTemplate<String, Object> redisTemplate;
    private final ProviderStrategyService providerService;
    
    public void queueLowPriority(String recipient, String message, String channel) {
        String key = "batch:" + channel + ":" + recipient;
        redisTemplate.opsForList().rightPush(key, message);
        redisTemplate.expire(key, 15, TimeUnit.MINUTES);
        log.debug("📦 Queued {} message for batching: {}", channel, recipient);
    }
    
    public void queueMerchantPayment(String merchantId, BigDecimal amount) {
        String key = "merchant:payments:" + merchantId;
        redisTemplate.opsForList().rightPush(key, amount.toString());
        redisTemplate.expire(key, 60, TimeUnit.SECONDS);
        
        Long count = redisTemplate.opsForList().size(key);
        if (count != null && count >= 20) {
            flushMerchantBatch(merchantId);
        }
    }
    
    private void flushMerchantBatch(String merchantId) {
        String key = "merchant:payments:" + merchantId;
        List<Object> amounts = redisTemplate.opsForList().range(key, 0, -1);
        
        if (amounts != null && !amounts.isEmpty()) {
            BigDecimal total = amounts.stream()
                .map(a -> new BigDecimal(a.toString()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            String message = String.format("You received %d transfers totaling $%.2f", 
                amounts.size(), total);
            
            providerService.sendSms("+merchant-phone", message);
            redisTemplate.delete(key);
            log.info("💰 Sent aggregated merchant notification: {} payments = ${}", amounts.size(), total);
        }
    }
    
    @Scheduled(fixedDelay = 900000)
    public void processBatches() {
        log.info("🔄 Processing batched notifications...");
        Set<String> keys = redisTemplate.keys("batch:*");
        
        if (keys != null) {
            keys.forEach(key -> {
                List<Object> messages = redisTemplate.opsForList().range(key, 0, -1);
                if (messages != null && !messages.isEmpty()) {
                    String[] parts = key.split(":");
                    String channel = parts[1];
                    String recipient = parts[2];
                    
                    String combined = String.join("\n", messages.stream()
                        .map(Object::toString)
                        .toList());
                    
                    if ("SMS".equals(channel)) {
                        providerService.sendSms(recipient, combined);
                    } else if ("EMAIL".equals(channel)) {
                        providerService.sendEmail(recipient, combined);
                    }
                    
                    redisTemplate.delete(key);
                    log.info("✅ Sent batched {} to {}: {} messages", channel, recipient, messages.size());
                }
            });
        }
    }
}
