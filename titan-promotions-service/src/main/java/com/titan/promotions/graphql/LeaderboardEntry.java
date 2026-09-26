package com.titan.promotions.graphql;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class LeaderboardEntry {
    private Long accountId;
    private String username;
    private BigDecimal totalSpent;
    private Integer rank;
    private Long campaignId;
}
