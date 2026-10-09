package com.titan.titancorebanking.controller;

import com.titan.titancorebanking.dto.request.TransactionRequest;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.ScheduledTransaction;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.ScheduledTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;

/**
 * Controller for managing scheduled/recurring transactions.
 *
 * SECURITY NOTE: This endpoint should be protected with authentication.
 * Users should only be able to schedule transactions from their own accounts.
 */
@RestController
@RequestMapping("/api/v1/scheduled-transactions")
@RequiredArgsConstructor
@Slf4j
public class ScheduledTransactionController {

    private final ScheduledTransactionRepository repository;
    private final AccountRepository accountRepository; // ✅ FIX: Added AccountRepository injection

    /**
     * Schedule a future or recurring transaction.
     *
     * BUG FIX: Previously parsed account number as ID (wrong) and omitted NOT NULL fields.
     * Now properly looks up account by account number and sets all required fields.
     *
     * @param request Transaction details including account numbers and amount
     * @return The created ScheduledTransaction entity
     */
    @PostMapping
    public ResponseEntity<?> schedule(@RequestBody TransactionRequest request) {
        // ✅ FIX: Validate required fields upfront
        if (request.fromAccountNumber() == null || request.toAccountNumber() == null) {
            throw new IllegalArgumentException("Both fromAccountNumber and toAccountNumber are required");
        }
        if (request.amount() == null || request.amount().signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }

        // ✅ FIX: Properly look up account by account number, not parse as ID
        Account fromAccount = accountRepository.findByAccountNumber(request.fromAccountNumber())
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + request.fromAccountNumber()));

        // ✅ FIX: Validate destination account exists
        accountRepository.findByAccountNumber(request.toAccountNumber())
                .orElseThrow(() -> new IllegalArgumentException("Destination account not found: " + request.toAccountNumber()));

        // ✅ FIX: Determine frequency from transactionType, default to ONCE
        String frequency = request.transactionType() != null ? request.transactionType() : "ONCE";
        LocalDateTime now = LocalDateTime.now();

        // ✅ FIX: Set ALL NOT NULL fields properly
        var st = ScheduledTransaction.builder()
                .fromAccountId(fromAccount.getId())           // ✅ Real account ID, not parsed account number
                .toAccountNumber(request.toAccountNumber())   // ✅ Was missing (NOT NULL violation)
                .amount(request.amount())
                .frequency(frequency)                         // ✅ Was missing (NOT NULL violation)
                .status("PENDING")
                .startDate(now)                               // ✅ Was missing (NOT NULL violation)
                .scheduledDate(now.plusDays(1))              // Default: execute tomorrow
                .createdAt(now)                               // ✅ Was missing
                .build();

        ScheduledTransaction saved = repository.save(st);
        log.info("Scheduled transaction created: ID={}, from account ID={}, to account={}, amount={}, frequency={}",
                saved.getId(), saved.getFromAccountId(), saved.getToAccountNumber(), saved.getAmount(), saved.getFrequency());

        return ResponseEntity.ok(saved);
    }
}