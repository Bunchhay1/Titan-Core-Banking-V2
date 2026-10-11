package com.titan.titancorebanking.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record GeneratePayerQrRequest(
        @NotBlank(message = "Payer account number is required")
        String payerAccountNumber,

        @NotNull(message = "Amount is required for send-by-QR")
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        BigDecimal amount,

        @NotBlank(message = "PIN is required to pre-authorize this payment")
        String pin,

        String note,
        Integer ttlMinutes
) {}