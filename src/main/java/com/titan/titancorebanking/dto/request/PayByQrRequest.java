package com.titan.titancorebanking.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record PayByQrRequest(
        @NotBlank(message = "QR code token is required")
        String qrCode,

        @NotBlank(message = "Payer account number is required")
        String payerAccountNumber,

        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        BigDecimal amount,

        @NotBlank(message = "PIN is required to authorize QR payment")
        String pin
) {}