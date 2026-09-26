package com.titan.titancorebanking.dto.request;

import jakarta.validation.constraints.*;

/**
 * Request body for POST /api/v1/atm/redeem
 *
 * The ATM terminal presents the 12-digit code entered by the customer.
 * Optionally includes the terminal ID for audit trails.
 */
public record AtmRedeemRequest(

    @NotBlank(message = "ATM code is required")
    @Size(min = 12, max = 12, message = "ATM code must be exactly 12 digits")
    @Pattern(regexp = "^[0-9]{12}$", message = "ATM code must contain only digits")
    String code,

    /** ATM terminal identifier – optional but useful for fraud investigation. */
    @Size(max = 50, message = "Terminal ID must not exceed 50 characters")
    String terminalId
) {}
