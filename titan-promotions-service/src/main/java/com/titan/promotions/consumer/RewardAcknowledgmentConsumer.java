package com.titan.promotions.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.titan.promotions.model.AppliedPromotion;
import com.titan.promotions.repository.AppliedPromotionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class RewardAcknowledgmentConsumer {
    
    private final AppliedPromotionRepository appliedPromotionRepository;
    private final ObjectMapper objectMapper;
    
    @KafkaListener(
        topics = "banking.rewards.acknowledgment",
        groupId = "promotions-reward-ack"
    )
    public void consumeAcknowledgment(String rawJson) {
        try {
            Map<String, Object> ack = objectMapper.readValue(rawJson, Map.class);
            String rewardEventId = (String) ack.get("rewardEventId");
            String status = (String) ack.get("status");
            
            if (rewardEventId != null) {
                appliedPromotionRepository.findByRewardEventId(rewardEventId).ifPresent(applied -> {
                    if ("SUCCESS".equals(status)) {
                        applied.setRewardStatus(AppliedPromotion.RewardStatus.DISBURSED);
                        log.info("Reward disbursed for promotion {}", applied.getId());
                    } else {
                        applied.setRewardStatus(AppliedPromotion.RewardStatus.FAILED);
                        log.error("Reward disbursement failed for promotion {}", applied.getId());
                    }
                    appliedPromotionRepository.save(applied);
                });
            }
        } catch (Exception e) {
            log.error("Failed to parse reward acknowledgment payload: {}", rawJson, e);
        }
    }
}
