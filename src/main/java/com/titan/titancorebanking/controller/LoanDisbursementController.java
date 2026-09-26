package com.titan.titancorebanking.controller;

import com.titan.titancorebanking.enums.TransactionStatus;
import com.titan.titancorebanking.enums.TransactionType;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Internal endpoint called by titan-loans-service to credit the loan amount
 * into the borrower's account upon loan approval.
 *
 * POST /api/v1/transactions/internal/disburse-loan
 * Body: { "accountId": 123, "amount": 10000.00, "loanId": 1, "reason": "Loan disbursement #1" }
 *
 * Open for internal service-to-service calls only (protected by network policy in production).
 */
@RestController
@RequestMapping("/api/v1/transactions/internal")
@RequiredArgsConstructor
@Slf4j
public class LoanDisbursementController {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    @PostMapping("/disburse-loan")
    public ResponseEntity<Map<String, Object>> disburseLoan(@RequestBody Map<String, Object> payload) {

        Long accountId = Long.valueOf(payload.get("accountId").toString());
        BigDecimal amount = new BigDecimal(payload.get("amount").toString());
        String reason = payload.getOrDefault("reason", "Loan disbursement").toString();

        log.info("💰 [LoanDisburse] Crediting ${} to account {} — reason: {}", amount, accountId, reason);

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

        // Credit the loan amount
        account.setBalance(account.getBalance().add(amount));
        accountRepository.save(account);

        // Record as a LOAN_DISBURSEMENT transaction
        Transaction tx = Transaction.builder()
                .toAccount(account)
                .transactionType(TransactionType.LOAN_DISBURSEMENT)
                .amount(amount)
                .status(TransactionStatus.SUCCESS)
                .note(reason)
                .timestamp(LocalDateTime.now())
                .transactionReference("LOAN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .build();
        transactionRepository.save(tx);

        log.info("✅ [LoanDisburse] ${} credited — new balance=${} txRef={}", amount, account.getBalance(), tx.getTransactionReference());

        return ResponseEntity.ok(Map.of(
                "message", "Loan disbursed successfully",
                "accountId", accountId,
                "amountCredited", amount,
                "newBalance", account.getBalance(),
                "transactionRef", tx.getTransactionReference()
        ));
    }
}
