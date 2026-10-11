package com.titan.titancorebanking.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record AccountRequest(
        @NotBlank(message = "Account type is required (e.g., SAVINGS, CHECKING)")
        String accountType,

        @NotBlank(message = "Currency is required (e.g., USD, EUR, KHR)")
        String currency,

        @DecimalMin(value = "0.00", message = "Initial deposit cannot be negative")
        BigDecimal initialDeposit
) {}