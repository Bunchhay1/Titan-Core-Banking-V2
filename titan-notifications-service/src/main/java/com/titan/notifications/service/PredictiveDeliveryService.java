package com.titan.notifications.service;

import com.titan.notifications.strategy.ProviderStrategyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class PredictiveDeliveryService {
    
    private final RedisTemplate<String, Object> redisTemplate;
    private final ProviderStrategyService providerService;
    
    public void scheduleOptimalDelivery(String userId, String channel, String recipient, String message, long optimalTimestamp) {
        String key = "scheduled:notification:" + UUID.randomUUID();
        Map<String, Object> notification = Map.of(
            "userId", userId,
            "channel", channel,
            "recipient", recipient,
            "message", message,
            "scheduledFor", optimalTimestamp
        );
        
        redisTemplate.opsForValue().set(key, notification, 24, TimeUnit.HOURS);
        redisTemplate.opsForZSet().add("scheduled:queue", key, optimalTimestamp);
        
        log.info("📅 Scheduled notification for user {} at {}", userId, Instant.ofEpochMilli(optimalTimestamp));
    }
    
    @Scheduled(fixedDelay = 10000)
    public void processScheduledNotifications() {
        try {
            if (redisTemplate == null) {
                log.trace("RedisTemplate is null, skipping scheduled notifications processing.");
                return;
            }
            long now = System.currentTimeMillis();
            Set<Object> ready = redisTemplate.opsForZSet().rangeByScore("scheduled:queue", 0, now);
            
            if (ready != null && !ready.isEmpty()) {
                log.info("⏰ Processing {} scheduled notifications", ready.size());
                ready.forEach(key -> {
                    Map<String, Object> notification = (Map<String, Object>) redisTemplate.opsForValue().get(key);
                    if (notification != null) {
                        String channel = (String) notification.get("channel");
                        String recipient = (String) notification.get("recipient");
                        String message = (String) notification.get("message");
                        
                        if ("SMS".equals(channel)) {
                            providerService.sendSms(recipient, message);
                        } else if ("EMAIL".equals(channel)) {
                            providerService.sendEmail(recipient, message);
                        }
                        
                        redisTemplate.delete((String) key);
                        redisTemplate.opsForZSet().remove("scheduled:queue", key);
                    }
                });
            }
        } catch (Exception e) {
            log.error("❌ Error in scheduled notifications processing: {}", e.getMessage(), e);
        }
    }
}
