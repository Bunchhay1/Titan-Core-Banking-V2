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
 * Internal endpoint called by titan-loans-service to deduct loan processing fees.
 *
 * POST /api/v1/transactions/internal/deduct-fee
 * Body: { "accountId": 123, "amount": 40.00, "reason": "Loan processing fee" }
 *
 * This endpoint is open (no user auth required) for internal service-to-service calls.
 * It is protected by network policy in production (only accessible from titan-loans-service).
 */
@RestController
@RequestMapping("/api/v1/transactions/internal")
@RequiredArgsConstructor
@Slf4j
public class LoanFeeController {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    @PostMapping("/deduct-fee")
    public ResponseEntity<Map<String, Object>> deductFee(@RequestBody Map<String, Object> payload) {

        Long accountId = Long.valueOf(payload.get("accountId").toString());
        BigDecimal amount = new BigDecimal(payload.get("amount").toString());
        String reason = payload.getOrDefault("reason", "Loan processing fee").toString();

        log.info("💳 [LoanFee] Deducting fee ${} from account {} — reason: {}", amount, accountId, reason);

        // Find account
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

        // Check sufficient balance
        if (account.getBalance().compareTo(amount) < 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Insufficient balance for fee deduction",
                    "balance", account.getBalance(),
                    "feeRequired", amount
            ));
        }

        // Deduct balance
        account.setBalance(account.getBalance().subtract(amount));
        accountRepository.save(account);

        // Record the fee transaction
        Transaction tx = Transaction.builder()
                .fromAccount(account)
                .transactionType(TransactionType.FEE)
                .amount(amount)
                .status(TransactionStatus.SUCCESS)
                .note(reason)
                .timestamp(LocalDateTime.now())
                .transactionReference("FEE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .build();
        transactionRepository.save(tx);

        log.info("✅ [LoanFee] Fee ${} deducted — new balance=${} txRef={}", amount, account.getBalance(), tx.getTransactionReference());

        return ResponseEntity.ok(Map.of(
                "message", "Fee deducted successfully",
                "accountId", accountId,
                "feeDeducted", amount,
                "newBalance", account.getBalance(),
                "transactionRef", tx.getTransactionReference()
        ));
    }
}
