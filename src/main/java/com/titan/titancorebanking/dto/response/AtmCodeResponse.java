package com.titan.titancorebanking.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Response for both generate and redeem ATM operations.
 */
public record AtmCodeResponse(
    Long id,

    /** The 12-digit one-time code to enter at the ATM. */
    String code,

    String accountNumber,
    BigDecimal amount,

    /** PENDING | USED | EXPIRED | CANCELLED */
    String status,

    /** When the code will expire (10 minutes after generation). */
    LocalDateTime expiresAt,

    /** Populated only after a successful redeem. */
    LocalDateTime redeemedAt,

    LocalDateTime createdAt,

    /** Human-readable message, e.g. "Code generated. Valid for 10 minutes." */
    String message
) {}
