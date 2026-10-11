package com.titan.titancorebanking.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * Immutable Record strictly defining the incoming contract. (ការកំណត់ទម្រង់ទិន្នន័យឲ្យបានច្បាស់លាស់)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RewardEventDto(
        String eventId,
        Long accountId,
        BigDecimal rewardAmount,
        String description,
        String currency
) {
    public boolean isValid() {
        return eventId != null &&
                accountId != null &&
                rewardAmount != null &&
                rewardAmount.compareTo(BigDecimal.ZERO) > 0;
    }

    public String getCurrencySafe() {
        return currency != null && !currency.isBlank() ? currency : "USD";
    }
}