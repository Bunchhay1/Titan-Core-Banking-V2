package com.titan.promotions.service;

import com.titan.promotions.ai.DynamicPricingService;
import com.titan.promotions.config.PromotionRuleProperties;
import com.titan.promotions.event.TransactionCompletedEvent;
import com.titan.promotions.federation.MerchantFederationService;
import com.titan.promotions.graph.ReferralGraphService;
import com.titan.promotions.graphql.LeaderboardController;
import com.titan.promotions.graphql.LeaderboardEntry;
import com.titan.promotions.model.AppliedPromotion;
import com.titan.promotions.repository.AppliedPromotionRepository;
import com.titan.promotions.shadow.ShadowRuleEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class PromotionService {
    
    private final AppliedPromotionRepository promotionRepository;
    private final PromotionRuleProperties promotionRuleProperties;
    private final ReferralGraphService referralGraphService;
    private final DynamicPricingService dynamicPricingService;
    private final ShadowRuleEngine shadowRuleEngine;
    private final MerchantFederationService merchantFederationService;
    private final LeaderboardController leaderboardController;
    
    private static final BigDecimal CASHBACK_THRESHOLD = new BigDecimal("100.00");
    private static final BigDecimal CASHBACK_PERCENTAGE = new BigDecimal("0.02");
    
    @Transactional("transactionManager")
    public void evaluatePromotions(TransactionCompletedEvent event) {
        log.info("Evaluating promotions for transaction: {}", event.getTransactionId());
        if (event == null || event.getAmount() == null) {
            log.warn("Skipping promotion evaluation due to missing event or amount");
            return;
        }

        // NOTE: Deposit Bonus (DEPOSIT_BONUS_JUL_AUG_2026) is handled exclusively by
        // PromotionEvaluationService.evaluateTransaction() which is called BEFORE this method
        // in TransactionEventConsumer. Do NOT call depositPromotionService.evaluateAndApply()
        // here — it would fire a second reward for the same deposit (duplicate notification).

        // Task 5: Shadow rule evaluation
        shadowRuleEngine.evaluateShadowRule(999L, "#amount > 50", event, new BigDecimal("10.00"));
        
        // Task 7: Merchant federation
        String tenantId = extractTenantId(event);
        if (tenantId != null) {
            merchantFederationService.evaluateMerchantCampaigns(event, tenantId);
        }
        
        // Task 1: Referral rewards
        Map<Long, BigDecimal> referralRewards;
        try {
            referralRewards = referralGraphService.calculateReferralRewards(
                event.getAccountId(), event.getAmount()
            );
        } catch (Exception e) {
            log.warn("[REFERRAL] Neo4j/Referral reward calculation failed: {}", e.getMessage());
            referralRewards = Map.of();
        }
        referralRewards.forEach((accountId, reward) -> 
            savePromotion(event, "REFERRAL_REWARD", reward, "Multi-level referral reward")
        );

        // Task 6: Dynamic pricing
        if (event.getAmount().compareTo(CASHBACK_THRESHOLD) > 0) {
            BigDecimal baseReward = event.getAmount().multiply(CASHBACK_PERCENTAGE);
            BigDecimal optimizedReward = dynamicPricingService.getOptimalReward(1L, event.getAccountId(), baseReward);
            
            savePromotion(event, "CASHBACK", optimizedReward,
                    String.format("AI-optimized cashback on transaction of %s %s", event.getAmount(), event.getCurrency()));
            
            // Task 4: Emit leaderboard update
            leaderboardController.emitLeaderboardUpdate(
                new LeaderboardEntry(event.getAccountId(), "User" + event.getAccountId(), event.getAmount(), 1, 1L)
            );
        }

        applyMemberBonus(event);
        applyCoinPoints(event);
    }

    private void applyMemberBonus(TransactionCompletedEvent event) {
        var rule = promotionRuleProperties.getMemberBonus();
        if (!rule.isEnabled() || !matchesTransactionType(event, rule.getTransactionType()) 
            || !matchesChannel(event, rule.getChannel())) {
            return;
        }
        if (event.getAmount().compareTo(rule.getThreshold()) < 0) return;
        
        savePromotion(event, "MEMBER_DEPOSIT_BONUS", rule.getBonusAmount(),
                String.format(Locale.US, "Member digital banking bonus: %s %s for deposit %s", 
                    rule.getBonusAmount(), event.getCurrency(), event.getAmount()));
    }

    private void applyCoinPoints(TransactionCompletedEvent event) {
        var rule = promotionRuleProperties.getCoinPoints();
        if (!rule.isEnabled() || !matchesTransactionType(event, rule.getTransactionType()) 
            || !matchesChannel(event, rule.getChannel())) {
            return;
        }
        
        BigDecimal points = event.getAmount().multiply(rule.getPointsPerCurrency()).setScale(0, RoundingMode.DOWN);
        if (rule.getMaxPointsPerTransaction() != null && rule.getMaxPointsPerTransaction().compareTo(BigDecimal.ZERO) > 0) {
            points = points.min(rule.getMaxPointsPerTransaction());
        }
        if (points.compareTo(BigDecimal.ZERO) <= 0) return;
        
        savePromotion(event, "COIN_POINTS", points,
                String.format(Locale.US, "Earned %s coin points for deposit %s %s", points, event.getAmount(), event.getCurrency()));
    }

    private boolean matchesTransactionType(TransactionCompletedEvent event, String transactionType) {
        return transactionType == null || transactionType.isBlank() || transactionType.equalsIgnoreCase(event.getTransactionType());
    }

    private boolean matchesChannel(TransactionCompletedEvent event, String channel) {
        if (channel == null || channel.isBlank()) return true;
        Map<String, String> metadata = event.getMetadata();
        if (metadata == null || metadata.isEmpty()) return false;
        String channelValue = metadata.get("channel");
        return channelValue != null && channel.equalsIgnoreCase(channelValue);
    }

    private String extractTenantId(TransactionCompletedEvent event) {
        Map<String, String> metadata = event.getMetadata();
        return metadata != null ? metadata.get("tenantId") : null;
    }

    private void savePromotion(TransactionCompletedEvent event, String type, BigDecimal amount, String description) {
        savePromotion(event, type, amount, description, null);
    }

    private void savePromotion(TransactionCompletedEvent event, String type, BigDecimal amount,
                                String description, Long campaignId) {
        AppliedPromotion promotion = AppliedPromotion.builder()
                .transactionId(parseTransactionId(event.getTransactionId()))
                .accountId(event.getAccountId() != null ? event.getAccountId() : 0L)
                .campaignId(campaignId != null ? campaignId : 0L)  // 0 = system/legacy promotion
                .promotionType(type)
                .promotionAmount(amount)
                .appliedAt(LocalDateTime.now())
                .description(description)
                .build();

        promotionRepository.save(promotion);
        log.info("Applied promotion [{}]: {} for transaction {}", type, amount, event.getTransactionId());
    }

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
