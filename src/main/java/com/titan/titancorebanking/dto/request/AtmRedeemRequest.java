package com.titan.titancorebanking.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Immutable ATM Redemption Request.
 * Used by the ATM terminal to redeem the 12-digit code[cite: 1].
 */
public record AtmRedeemRequest(
        @NotBlank(message = "ATM code is required")
        @Pattern(regexp = "^\\d{12}$", message = "ATM code must be exactly 12 digits")
        String code,

        @NotBlank(message = "Terminal ID is required for audit tracing")
        String terminalId
) {}