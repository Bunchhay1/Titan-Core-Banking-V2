package com.titan.titancorebanking.service;

import com.titan.titancorebanking.model.LedgerEntry;
import com.titan.titancorebanking.repository.LedgerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class DoubleEntryService {

    private final LedgerRepository ledgerRepository;

    /**
     * Creates balanced debit and credit entries for a transaction.
     * Propagation.MANDATORY strictly enforces that this method cannot be executed
     * outside of an existing transaction, mathematically guaranteeing no money
     * moves without a corresponding atomic ledger entry.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createDoubleEntry(Long transactionId,
                                  Long debitAccountId,
                                  Long creditAccountId,
                                  BigDecimal amount,
                                  String description,
                                  String userId) {

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }

        LocalDateTime now = LocalDateTime.now();

        LedgerEntry debitEntry = LedgerEntry.builder()
                .transactionId(transactionId)
                .accountId(debitAccountId)
                .entryType(LedgerEntry.EntryType.DEBIT)
                .amount(amount)
                .entryDate(now)
                .description(description)
                .createdBy(userId)
                .build();

        LedgerEntry creditEntry = LedgerEntry.builder()
                .transactionId(transactionId)
                .accountId(creditAccountId)
                .entryType(LedgerEntry.EntryType.CREDIT)
                .amount(amount)
                .entryDate(now)
                .description(description)
                .createdBy(userId)
                .build();

        ledgerRepository.save(debitEntry);
        ledgerRepository.save(creditEntry);

        log.debug("Created double-entry for transaction {} - Debit: {}, Credit: {}, Amount: {}",
                transactionId, debitAccountId, creditAccountId, amount);
    }

    @Transactional(readOnly = true)
    public BigDecimal getAccountBalance(Long accountId) {
        BigDecimal balance = ledgerRepository.calculateAccountBalance(accountId);
        return balance != null ? balance : BigDecimal.ZERO;
    }
}