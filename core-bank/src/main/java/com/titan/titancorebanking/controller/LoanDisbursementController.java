package com.titan.titancorebanking.controller;

import com.titan.titancorebanking.enums.TransactionStatus;
import com.titan.titancorebanking.enums.TransactionType;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
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
 * Headers: X-Internal-Api-Key: <secret-key>
 * Body: { "accountId": 123, "amount": 10000.00, "loanId": 1, "reason": "Loan disbursement #1" }
 *
 * SECURITY FIX: Now requires internal API key authentication.
 * Protected by X-Internal-Api-Key header validation (service-to-service authentication).
 * Additional network-level protection should be applied in production (IP whitelisting, VPC).
 */
@RestController
@RequestMapping("/api/v1/transactions/internal")
@RequiredArgsConstructor
@Slf4j
public class LoanDisbursementController {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    @Value("${internal.api.key:CHANGE_ME_IN_PRODUCTION}")
    private String internalApiKey;

    /**
     * Disburse loan amount to borrower's account.
     *
     * BUG FIX: Previously had NO authentication - anyone could credit any account.
     * Now requires X-Internal-Api-Key header for service-to-service authentication.
     *
     * @param payload Request body with accountId, amount, loanId, and reason
     * @param apiKey Internal API key from X-Internal-Api-Key header
     * @return Disbursement result with transaction reference
     */
    @PostMapping("/disburse-loan")
    public ResponseEntity<Map<String, Object>> disburseLoan(
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {

        // ✅ FIX: Validate internal API key before processing
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("🚫 [LoanDisburse] Unauthorized attempt - missing API key");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Missing X-Internal-Api-Key header"));
        }

        if (!internalApiKey.equals(apiKey)) {
            log.warn("🚫 [LoanDisburse] Unauthorized attempt - invalid API key: {}", apiKey);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Invalid internal API key"));
        }

        // ✅ Validation: Required fields
        if (!payload.containsKey("accountId") || !payload.containsKey("amount")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Missing required fields: accountId, amount"));
        }

        Long accountId = Long.valueOf(payload.get("accountId").toString());
        BigDecimal amount = new BigDecimal(payload.get("amount").toString());
        String reason = payload.getOrDefault("reason", "Loan disbursement").toString();

        // ✅ Validation: Amount must be positive
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Amount must be positive"));
        }

        log.info("💰 [LoanDisburse] Authorized request - Crediting ${} to account {} — reason: {}",
                amount, accountId, reason);

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

        log.info("✅ [LoanDisburse] ${} credited — new balance=${} txRef={}",
                amount, account.getBalance(), tx.getTransactionReference());

        return ResponseEntity.ok(Map.of(
                "message", "Loan disbursed successfully",
                "accountId", accountId,
                "amountCredited", amount,
                "newBalance", account.getBalance(),
                "transactionRef", tx.getTransactionReference()
        ));
    }
}
