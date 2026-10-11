package com.titan.titancorebanking.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Immutable Transaction Response Record.
 * Strips all internal database identifiers (except the public transaction reference)
 * and sensitive nested entity relationships.
 */
public record TransactionResponse(
        Long id,
        String type,
        BigDecimal amount,
        String fromAccount,
        String toAccount,
        String status,
        String note,
        LocalDateTime timestamp,
        String currency,
        BigDecimal fee,
        String transactionReference
) {}