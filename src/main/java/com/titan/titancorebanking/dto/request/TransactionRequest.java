package com.titan.titancorebanking.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Immutable Transaction Request Record.
 * Utilizes Jakarta Validation to enforce strict payload constraints before reaching the service layer.
 */
public record TransactionRequest(
        @NotBlank(message = "Source account number cannot be empty")
        String fromAccountNumber,

        String toAccountNumber,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        BigDecimal amount,

        String pin,

        String note,

        String swiftCode,

        String iban,

        // Used internally for withIdempotencyKey copying
        String idempotencyKey
) {
    /**
     * Wither method to safely enrich the immutable record with an idempotency key
     * extracted from HTTP headers in the controller layer.
     */
    public TransactionRequest withIdempotencyKey(String idempotencyKey) {
        return new TransactionRequest(
                this.fromAccountNumber,
                this.toAccountNumber,
                this.amount,
                this.pin,
                this.note,
                this.swiftCode,
                this.iban,
                idempotencyKey
        );
    }
}