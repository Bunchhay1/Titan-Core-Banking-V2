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

        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC); // always UTC

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

    /**
     * Creates transfer double-entry records accounting for fee.
     * The sender is debited for total deduction (amount + fee).
     * The receiver is credited for base transfer amount.
     * Propagation.MANDATORY strictly enforces execution inside an existing transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createTransferDoubleEntry(Long transactionId,
                                          Long debitAccountId,
                                          Long creditAccountId,
                                          BigDecimal amount,
                                          BigDecimal fee,
                                          String description,
                                          String userId) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        BigDecimal actualFee = fee != null ? fee : BigDecimal.ZERO;
        BigDecimal totalDeduction = amount.add(actualFee);

        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC); // always UTC

        LedgerEntry debitEntry = LedgerEntry.builder()
                .transactionId(transactionId)
                .accountId(debitAccountId)
                .entryType(LedgerEntry.EntryType.DEBIT)
                .amount(totalDeduction)
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

        log.debug("Created transfer double-entry for transaction {} - Debit: {} (total: {}), Credit: {} (amount: {})",
                transactionId, debitAccountId, totalDeduction, creditAccountId, amount);
    }

    @Transactional(readOnly = true)
    public BigDecimal getAccountBalance(Long accountId) {
        BigDecimal balance = ledgerRepository.calculateAccountBalance(accountId);
        return balance != null ? balance : BigDecimal.ZERO;
    }
}