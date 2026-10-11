package com.titan.titancorebanking.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Immutable ATM Code Response.
 */
public record AtmCodeResponse(
        Long id,
        String code,
        String accountNumber,
        BigDecimal amount,
        String status,
        LocalDateTime expiresAt,
        LocalDateTime redeemedAt,
        LocalDateTime createdAt,
        String message
) {}