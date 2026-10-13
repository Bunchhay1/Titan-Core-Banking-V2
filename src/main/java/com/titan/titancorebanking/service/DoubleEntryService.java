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
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DoubleEntryService {

    private final LedgerRepository ledgerRepository;

    /**
     * Creates balanced debit and credit entries for a transaction.
     * Enforces Propagation.MANDATORY: cannot be called outside an active transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createDoubleEntry(Long transactionId,
                                  Long debitAccountId,
                                  Long creditAccountId,
                                  BigDecimal amount,
                                  String description,
                                  String userId) {

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Ledger transaction amount must be positive");
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

        // Optimized single batch insertion instead of dual sequential queries
        ledgerRepository.saveAll(List.of(debitEntry, creditEntry));

        log.debug("Double-entry journal recorded: txId={} [Debit: {} | Credit: {} | Amount: {}]",
                transactionId, debitAccountId, creditAccountId, amount);
    }

    @Transactional(readOnly = true)
    public BigDecimal getAccountBalance(Long accountId) {
        BigDecimal balance = ledgerRepository.calculateAccountBalance(accountId);
        return balance != null ? balance : BigDecimal.ZERO;
    }
}