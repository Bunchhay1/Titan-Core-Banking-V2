package com.titan.titancorebanking.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ✅ JAVA 21: Immutable Record Response
 */
public record TransactionResponse(
    Long id,
    String type,
    BigDecimal amount,
    String fromAccountNumber,
    String toAccountNumber,
    String status,
    String note,
    LocalDateTime timestamp,
    String currency,
    BigDecimal fee,
    String referenceNumber
) {}