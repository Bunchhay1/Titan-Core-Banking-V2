package com.titan.titancorebanking.model;

import com.titan.titancorebanking.enums.Currency;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Canonical monetary model capturing the complete financial breakdown of a transfer.
 * Encapsulates source debit, fee, FX rate, and target credit to prevent discrepancies
 * between transaction records, ledgers, and audit trails.
 */
public record MonetaryTransferBreakdown(
        BigDecimal sourceAmount,
        Currency sourceCurrency,
        BigDecimal feeAmount,
        Currency feeCurrency,
        BigDecimal totalDeduction,
        BigDecimal targetAmount,
        Currency targetCurrency,
        BigDecimal exchangeRate,
        String exchangeRateRef
) {
    public MonetaryTransferBreakdown {
        Objects.requireNonNull(sourceAmount, "sourceAmount must not be null");
        Objects.requireNonNull(sourceCurrency, "sourceCurrency must not be null");
        Objects.requireNonNull(targetCurrency, "targetCurrency must not be null");
        if (sourceAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("sourceAmount must be positive");
        }
        if (feeAmount == null) {
            feeAmount = BigDecimal.ZERO;
        }
        if (feeCurrency == null) {
            feeCurrency = sourceCurrency;
        }
        if (totalDeduction == null) {
            totalDeduction = sourceAmount.add(feeAmount);
        }
        if (targetAmount == null) {
            targetAmount = sourceAmount;
        }
        if (exchangeRate == null) {
            exchangeRate = BigDecimal.ONE;
        }
    }
}
