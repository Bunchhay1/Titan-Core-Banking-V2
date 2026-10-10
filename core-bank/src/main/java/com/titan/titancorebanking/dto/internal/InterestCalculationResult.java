package com.titan.titancorebanking.dto.internal;

import com.titan.titancorebanking.model.Account;
import java.math.BigDecimal;

/**
 * Internal DTO for Spring Batch processing.
 * Encapsulates the account and its computed interest before ledger commitment.
 */
public record InterestCalculationResult(Account account, BigDecimal interestAmount) {}