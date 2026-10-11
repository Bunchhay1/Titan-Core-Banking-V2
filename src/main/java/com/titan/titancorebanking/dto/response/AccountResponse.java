package com.titan.titancorebanking.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Immutable Account Response Record.
 * Prevents Hibernate LazyInitializationExceptions by strictly separating the API contract
 * from the JPA persistence context.
 */
public record AccountResponse(
        Long id,
        String accountNumber,
        String accountType,
        String currency,
        BigDecimal balance,
        BigDecimal overdraftLimit,
        String status,
        LocalDateTime createdAt
) {}