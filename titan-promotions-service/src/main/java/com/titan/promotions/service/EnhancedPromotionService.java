package com.titan.promotions.service;

import com.titan.promotions.ab.ABTestingService;
import com.titan.promotions.event.TransactionCompletedEvent;
import com.titan.promotions.eventsourcing.RuleEventStore;
import com.titan.promotions.fraud.FraudDefenseService;
import com.titan.promotions.model.AppliedPromotion;
import com.titan.promotions.personalization.PersonalizationEngine;
import com.titan.promotions.repository.AppliedPromotionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
@RequiredArgsConstructor
public class EnhancedPromotionService {
    
    private final AppliedPromotionRepository promotionRepository;
    private final ABTestingService abTestingService;
    private final PersonalizationEngine personalizationEngine;
    private final FraudDefenseService fraudDefenseService;
    private final RuleEventStore ruleEventStore;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    
    @Transactional
    public CompletableFuture<Void> evaluatePromotionsAsync(TransactionCompletedEvent event) {
        return CompletableFuture.runAsync(() -> {
            try {
                evaluatePromotions(event);
            } catch (Exception e) {
                log.error("Promotion evaluation failed for transaction {}", event.getTransactionId(), e);
            }
        });
    }
    
    private void evaluatePromotions(TransactionCompletedEvent event) {
        Long campaignId = 1L;
        
        // Task 6: A/B Testing - Assign variant
        ABTestingService.ABVariant variant = abTestingService.assignVariant(event.getAccountId(), campaignId);
        BigDecimal baseReward = abTestingService.calculateReward(variant, event.getAmount());
        
        // Task 4: AI Personalization - Adjust reward
        CompletableFuture<BigDecimal> personalizedRewardFuture = personalizationEngine.calculatePersonalizedReward(
            event.getAccountId(), baseReward);
        
        BigDecimal finalReward = personalizedRewardFuture.join();
        
        // Task 3: Fraud Defense - Check before granting high-value rewards
        Map<String, String> metadata = event.getMetadata();
        String deviceFingerprint = metadata != null ? metadata.get("deviceFingerprint") : null;
        String ipAddress = metadata != null ? metadata.get("ipAddress") : null;
        
        boolean shouldGrant = fraudDefenseService.shouldGrantReward(
            event.getAccountId(), deviceFingerprint, ipAddress, finalReward);
        
        if (!shouldGrant) {
            log.warn("Reward blocked by fraud defense: account={}, amount={}", event.getAccountId(), finalReward);
            return;
        }
        
        // Save promotion
        AppliedPromotion promotion = AppliedPromotion.builder()
            .transactionId(parseTransactionId(event.getTransactionId()))
            .accountId(event.getAccountId() != null ? event.getAccountId() : 0L)
            .campaignId(campaignId)
            .promotionType("DYNAMIC_REWARD")
            .promotionAmount(finalReward)
            .abVariant(variant.name())
            .appliedAt(LocalDateTime.now())
            .description(String.format("Personalized %s reward: %s", variant, finalReward))
            .rewardStatus(AppliedPromotion.RewardStatus.PENDING)
            .build();
        
        promotionRepository.save(promotion);
        
        // Task 2: Saga - Send reward to ledger
        kafkaTemplate.send("ledger-rewards", new RewardDispatchEvent(promotion.getId(), event.getAccountId(), finalReward));
        
        // Task 6: A/B Testing - Record metrics
        abTestingService.recordMetric(variant, "rewards_granted", BigDecimal.ONE);
        abTestingService.recordMetric(variant, "total_reward_amount", finalReward);
        
        log.info("Promotion applied: id={}, variant={}, reward={}", promotion.getId(), variant, finalReward);
    }
    
    public record RewardDispatchEvent(Long promotionId, Long accountId, BigDecimal amount) {}

    private Long parseTransactionId(String transactionId) {
        if (transactionId == null || transactionId.isBlank()) return 0L;
        try {
            return Long.parseLong(transactionId.split("-")[0]);
        } catch (NumberFormatException e) {
            log.warn("Cannot parse transactionId '{}' as Long, using 0", transactionId);
            return 0L;
        }
    }
}
