package com.titan.promotions.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RewardGrantedEvent {
    private String eventId;
    private String eventType;
    private String eventVersion;
    private String timestamp;
    private String correlationId;
    
    private Long accountId;
    private Long transactionId;
    private Long campaignId;
    private BigDecimal rewardAmount;
    private String currency;
    private String description;
}
