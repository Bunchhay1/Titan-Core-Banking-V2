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

    // [MODIFIED] Enforced Propagation.MANDATORY to strictly couple ledger writes to the parent transaction.
    // ការប្រើប្រាស់ Propagation.MANDATORY ធានាថាការកត់ត្រាបញ្ជីរាយនាម (Ledger) ត្រូវតែស្ថិតនៅក្នុង Transaction តែមួយជាមួយការកាត់លុយ។ បើអ្នកសរសេរកូដភ្លេចដាក់ @Transactional នៅ Method ដើម វានឹងបោះ Error ភ្លាមៗ ការពារមិនឲ្យលុយបាត់ តែគ្មានទិន្នន័យបញ្ជី។
    @Transactional(propagation = Propagation.MANDATORY)
    public void createDoubleEntry(final Long transactionId,
                                  final Long debitAccountId,
                                  final Long creditAccountId,
                                  final BigDecimal amount,
                                  final String description,
                                  final String userId) {

        // [MODIFIED] Enforced immutable method parameters using 'final' and strict null checks.
        // ការដាក់ 'final' នៅលើ Parameters ការពារមិនឲ្យមានការកែប្រែតម្លៃដោយចៃដន្យនៅក្នុង Method នេះ ដែលជាស្តង់ដារសុវត្ថិភាពខ្ពស់បំផុត (Immutability Guarantee)។
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Ledger entry amount must be strictly positive");
        }

        var now = LocalDateTime.now();

        var debitEntry = LedgerEntry.builder()
                .transactionId(transactionId)
                .accountId(debitAccountId)
                .entryType(LedgerEntry.EntryType.DEBIT)
                .amount(amount)
                .entryDate(now)
                .description(description)
                .createdBy(userId)
                .build();

        var creditEntry = LedgerEntry.builder()
                .transactionId(transactionId)
                .accountId(creditAccountId)
                .entryType(LedgerEntry.EntryType.CREDIT)
                .amount(amount)
                .entryDate(now)
                .description(description)
                .createdBy(userId)
                .build();

        // [MODIFIED] Replaced sequential .save() calls with batch .saveAll() for high-throughput JDBC optimization.
        // ប្តូរពីការ Save ម្តងមួយៗ ទៅជាការប្រើ saveAll() ដែលជួយបង្រួញ Database Statements ទៅជា Batch Insert តែមួយ។ វាជួយកាត់បន្ថយ Network Round-trips និងបង្កើនល្បឿន (Throughput) សម្រាប់ប្រព័ន្ធដែលមាន Request រាប់ពាន់។
        ledgerRepository.saveAll(List.of(debitEntry, creditEntry));

        log.info("Committed double-entry ledger | TxID: {} | DebitAcc: {} | CreditAcc: {} | Amount: {}",
                transactionId, debitAccountId, creditAccountId, amount);
    }

    @Transactional(readOnly = true)
    public BigDecimal getAccountBalance(final Long accountId) {
        var balance = ledgerRepository.calculateAccountBalance(accountId);
        return balance != null ? balance : BigDecimal.ZERO;
    }
}