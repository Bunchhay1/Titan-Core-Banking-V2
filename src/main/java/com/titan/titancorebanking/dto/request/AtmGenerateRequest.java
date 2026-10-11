package com.titan.titancorebanking.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/**
 * Immutable ATM Generation Request.
 * Enforces strict PIN and amount validation at the API boundary[cite: 1].
 */
public record AtmGenerateRequest(
        @NotBlank(message = "Account number is required")
        String accountNumber,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "10.00", message = "Minimum ATM withdrawal is $10.00")
        BigDecimal amount,

        @NotBlank(message = "PIN is required")
        @Pattern(regexp = "^\\d{4,6}$", message = "PIN must be a 4 to 6 digit number")
        String pin
) {}