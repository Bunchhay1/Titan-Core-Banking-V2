package com.titan.promotions.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Applied Promotion Entity
 * Tracks promotions applied to transactions
 */
@Entity
@Table(name = "applied_promotions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppliedPromotion {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private Long transactionId;

    @Column(nullable = false)
    private Long accountId;

    /**
     * References campaigns.id. Nullable for system/legacy promotions
     * (REFERRAL_REWARD, CASHBACK, COIN_POINTS, MEMBER_DEPOSIT_BONUS)
     * that are not tied to a specific campaign row.
     * Sentinel value: 0L = system/legacy; null = unset.
     */
    @Column
    private Long campaignId;
    
    @Column(nullable = false, length = 50)
    private String promotionType;
    
    @Column(length = 10)
    private String abVariant; // Task 6: A/B testing
    
    @Column(precision = 5, scale = 2)
    private BigDecimal propensityScore; // Task 4: Personalization
    
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal promotionAmount;
    
    @Column(nullable = false)
    private LocalDateTime appliedAt;
    
    @Column(length = 500)
    private String description;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private RewardStatus rewardStatus = RewardStatus.PENDING;
    
    private String rewardEventId;
    
    public enum RewardStatus {
        PENDING, DISPATCHED, DISBURSED, FAILED
    }
}
