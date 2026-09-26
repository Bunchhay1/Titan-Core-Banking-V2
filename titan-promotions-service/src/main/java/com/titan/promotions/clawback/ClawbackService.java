package com.titan.promotions.clawback;

import com.titan.promotions.repository.AppliedPromotionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class ClawbackService {
    private final RewardClawbackRepository clawbackRepository;
    private final AppliedPromotionRepository promotionRepository;
    
    @KafkaListener(topics = "banking.transactions.refunded", groupId = "promotions-clawback")
    @Transactional
    public void handleRefund(Map<String, Object> refundEvent) {
        Long originalTxId = ((Number) refundEvent.get("originalTransactionId")).longValue();
        Long refundTxId = ((Number) refundEvent.get("transactionId")).longValue();
        Long accountId = ((Number) refundEvent.get("accountId")).longValue();
        
        if (clawbackRepository.existsByOriginalTransactionId(originalTxId)) {
            log.warn("Clawback already processed for transaction {}", originalTxId);
            return;
        }
        
        promotionRepository.findAll().stream()
            .filter(p -> p.getTransactionId().equals(originalTxId))
            .forEach(promotion -> {
                RewardClawback clawback = RewardClawback.builder()
                    .originalTransactionId(originalTxId)
                    .refundTransactionId(refundTxId)
                    .accountId(accountId)
                    .originalPromotionId(promotion.getId())
                    .clawbackAmount(promotion.getPromotionAmount())
                    .clawbackAt(LocalDateTime.now())
                    .status(RewardClawback.ClawbackStatus.COMPLETED)
                    .build();
                
                clawbackRepository.save(clawback);
                log.info("Clawback executed: {} from account {} for refund {}", 
                    promotion.getPromotionAmount(), accountId, refundTxId);
            });
    }
}
