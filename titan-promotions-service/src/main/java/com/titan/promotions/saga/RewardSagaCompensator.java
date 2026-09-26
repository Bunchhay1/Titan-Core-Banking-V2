package com.titan.promotions.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.titan.promotions.model.AppliedPromotion;
import com.titan.promotions.repository.AppliedPromotionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class RewardSagaCompensator {

    private final AppliedPromotionRepository promotionRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "ledger-reward-rejected", groupId = "promotion-saga")
    @Transactional
    public void compensateRejectedReward(String payload) {
        try {
            RewardRejectedEvent event = objectMapper.readValue(payload, RewardRejectedEvent.class);
            log.warn("Ledger rejected reward for promotion {}: {}", event.promotionId(), event.reason());

            AppliedPromotion promotion = promotionRepository.findById(event.promotionId())
                .orElseThrow(() -> new IllegalStateException("Promotion not found: " + event.promotionId()));

            promotion.setRewardStatus(AppliedPromotion.RewardStatus.FAILED);
            promotionRepository.save(promotion);

            String quotaKey = "campaign:" + promotion.getCampaignId() + ":quota";
            redisTemplate.opsForValue().decrement(quotaKey);

            log.info("Compensated promotion {}: status=FAILED, quota restored", event.promotionId());
        } catch (Exception e) {
            log.error("Failed to compensate rejected reward: {}", e.getMessage(), e);
        }
    }

    public record RewardRejectedEvent(Long promotionId, String reason) {}
}
