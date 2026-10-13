package com.titan.titancorebanking.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record TransactionRequest(
        @NotNull String fromAccountNumber,
        String toAccountNumber,
        @NotNull @DecimalMin(value = "0.01", message = "Amount must be greater than zero") BigDecimal amount,
        String pin,
        String note,
        String idempotencyKey,
        String swiftCode,
        String iban
) {
    // STAFF ENGINEER FIX: Prevent secret data leakage in logs and APM tracing
    @Override
    public String toString() {
        return "TransactionRequest{" +
                "fromAccountNumber='" + fromAccountNumber + '\'' +
                ", toAccountNumber='" + toAccountNumber + '\'' +
                ", amount=" + amount +
                ", note='" + note + '\'' +
                ", idempotencyKey='" + idempotencyKey + '\'' +
                ", pin='[PROTECTED]'" +
                '}';
    }
}