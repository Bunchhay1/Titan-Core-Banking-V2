package com.titan.titancorebanking.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

/**
 * Immutable QR Generation Request.
 * Amount is optional (null creates an open-amount QR)[cite: 1].
 */
public record GenerateQrRequest(
        @NotBlank(message = "Payee account number is required")
        String payeeAccountNumber,

        @DecimalMin(value = "0.01", message = "Amount must be greater than zero if specified")
        BigDecimal amount,

        String note,
        Integer ttlMinutes
) {}