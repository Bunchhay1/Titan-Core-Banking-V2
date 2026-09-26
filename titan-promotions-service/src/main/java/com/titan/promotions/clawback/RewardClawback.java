package com.titan.promotions.clawback;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "reward_clawbacks")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RewardClawback {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, unique = true)
    private Long originalTransactionId;
    
    @Column(nullable = false)
    private Long refundTransactionId;
    
    @Column(nullable = false)
    private Long accountId;
    
    @Column(nullable = false)
    private Long originalPromotionId;
    
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal clawbackAmount;
    
    @Column(nullable = false)
    private LocalDateTime clawbackAt;
    
    @Enumerated(EnumType.STRING)
    private ClawbackStatus status;
    
    public enum ClawbackStatus {
        PENDING, COMPLETED, FAILED
    }
}
