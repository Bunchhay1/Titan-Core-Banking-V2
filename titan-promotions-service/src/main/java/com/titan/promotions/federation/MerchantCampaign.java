package com.titan.promotions.federation;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "merchant_campaigns")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantCampaign {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String tenantId;
    
    @Column(nullable = false)
    private String merchantName;
    
    @Column(nullable = false)
    private Long merchantAccountId;
    
    @Column(nullable = false)
    private String campaignName;
    
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal totalBudget;
    
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal remainingBudget;
    
    @Column(nullable = false)
    private LocalDateTime startDate;
    
    @Column(nullable = false)
    private LocalDateTime endDate;
    
    @Column(length = 2000)
    private String ruleExpression;
    
    private Boolean active;
}
