package com.titan.promotions.repository;

import com.titan.promotions.model.AppliedPromotion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AppliedPromotionRepository extends JpaRepository<AppliedPromotion, Long> {
    
    List<AppliedPromotion> findByAccountId(Long accountId);
    
    List<AppliedPromotion> findByTransactionId(Long transactionId);
    
    Optional<AppliedPromotion> findByRewardEventId(String rewardEventId);

    /** Check if an account has already received a specific campaign reward (one-time guard) */
    boolean existsByAccountIdAndPromotionType(Long accountId, String promotionType);

    /**
     * Idempotency guard: returns true if a specific transaction has already had
     * a given promotion applied. Prevents double-bonus on duplicate Kafka delivery
     * or repeated REST calls for the same transactionId.
     */
    boolean existsByTransactionIdAndPromotionType(Long transactionId, String promotionType);
}

