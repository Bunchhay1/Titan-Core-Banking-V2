package com.titan.promotions.admin;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class CampaignRequest {
    private String campaignCode;
    private String name;
    private String ruleExpression;
    private BigDecimal rewardAmount;
    private Integer quotaLimit;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
}
