package com.titan.promotions.budget;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class BudgetMonitorService {
    
    private final RedisTemplate<String, String> redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    
    @Scheduled(fixedRate = 300000) // Every 5 minutes
    public void monitorBudgetBurnRate() {
        Thread.startVirtualThread(() -> {
            String campaignKey = "campaign:1:spent";
            String timestampKey = "campaign:1:last_check";
            
            String currentSpentStr = redisTemplate.opsForValue().get(campaignKey);
            String lastCheckStr = redisTemplate.opsForValue().get(timestampKey);
            
            if (currentSpentStr == null) return;
            
            BigDecimal currentSpent = new BigDecimal(currentSpentStr);
            BigDecimal budget = new BigDecimal("50000.00");
            
            if (lastCheckStr != null) {
                BigDecimal lastSpent = new BigDecimal(lastCheckStr);
                Duration elapsed = Duration.ofMinutes(5);
                BigDecimal burnRate = currentSpent.subtract(lastSpent).divide(new BigDecimal(elapsed.toMinutes()), 2, BigDecimal.ROUND_HALF_UP);
                
                // Predict exhaustion time
                BigDecimal remaining = budget.subtract(currentSpent);
                long minutesUntilExhaustion = remaining.divide(burnRate, 0, BigDecimal.ROUND_UP).longValue();
                
                if (minutesUntilExhaustion <= 120) {
                    log.error("CRITICAL: Campaign budget will exhaust in {} minutes at current burn rate ${}/min", 
                        minutesUntilExhaustion, burnRate);
                    
                    kafkaTemplate.send("admin-alerts", new BudgetAlert(1L, minutesUntilExhaustion, burnRate));
                }
            }
            
            redisTemplate.opsForValue().set(timestampKey, currentSpent.toString());
        });
    }
    
    public record BudgetAlert(Long campaignId, long minutesUntilExhaustion, BigDecimal burnRate) {}
}
