package com.titan.titancorebanking.mapper;

import com.titan.titancorebanking.dto.response.TransactionResponse;
import com.titan.titancorebanking.model.Transaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * TransactionMapper — Presentation-layer DTO mapper for transaction records.
 * Decouples domain entities and ledger models from API response models.
 */
@Component
public class TransactionMapper {

    public TransactionResponse toResponse(Transaction transaction) {
        if (transaction == null) {
            return null;
        }
        String currency = resolveCurrency(transaction);
        return new TransactionResponse(
                transaction.getId(),
                transaction.getTransactionType() != null ? transaction.getTransactionType().name() : null,
                transaction.getAmount(),
                transaction.getFromAccount() != null ? transaction.getFromAccount().getAccountNumber() : null,
                transaction.getToAccount() != null ? transaction.getToAccount().getAccountNumber() : null,
                transaction.getStatus() != null ? transaction.getStatus().name() : null,
                transaction.getNote(),
                transaction.getTimestamp(),
                currency,
                transaction.getFee() != null ? transaction.getFee() : BigDecimal.ZERO,
                transaction.getTransactionReference()
        );
    }

    private String resolveCurrency(Transaction transaction) {
        if (transaction.getFromAccount() != null && transaction.getFromAccount().getCurrency() != null) {
            return transaction.getFromAccount().getCurrency().name();
        }
        if (transaction.getToAccount() != null && transaction.getToAccount().getCurrency() != null) {
            return transaction.getToAccount().getCurrency().name();
        }
        throw new IllegalStateException("Transaction " + transaction.getId() + " has no determinable currency.");
    }
}
