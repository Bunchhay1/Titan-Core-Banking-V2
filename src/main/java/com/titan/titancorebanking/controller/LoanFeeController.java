package com.titan.titancorebanking.controller;

import com.titan.titancorebanking.enums.TransactionStatus;
import com.titan.titancorebanking.enums.TransactionType;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.TransactionRepository;
import com.titan.titancorebanking.service.DoubleEntryService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Internal Loan Fee Controller.
 * @Hidden from Swagger UI. Ensures atomic processing with DoubleEntryService integration.
 * Deducts loan processing fees and credits the Bank's Fee Income GL Account.
 */
@Hidden
@RestController
@RequestMapping("/api/v1/transactions/internal")
@RequiredArgsConstructor
@Slf4j
public class LoanFeeController {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final DoubleEntryService doubleEntryService;

    // Assuming ID 5 represents the Bank's Fee Income GL Account
    private static final Long FEE_INCOME_GL_ACCOUNT_ID = 5L;

    @PostMapping("/deduct-fee")
    @Transactional
    public ResponseEntity<Map<String, Object>> deductFee(@RequestBody FeeDeductionRequest payload) {
        log.info("  [LoanFee] Deducting fee ${} from account {}   reason: {}",
                payload.amount(), payload.accountId(), payload.reason());

        Account account = accountRepository.findByIdWithLock(payload.accountId())
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + payload.accountId()));

        if (account.getBalance().compareTo(payload.amount()) < 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Insufficient balance for fee deduction",
                    "balance", account.getBalance(),
                    "feeRequired", payload.amount()
            ));
        }

        account.setBalance(account.getBalance().subtract(payload.amount()));
        accountRepository.save(account);

        Transaction tx = Transaction.builder()
                .fromAccount(account)
                .transactionType(TransactionType.FEE)
                .amount(payload.amount())
                .status(TransactionStatus.SUCCESS)
                .note(payload.reason() != null ? payload.reason() : "Loan processing fee")
                .timestamp(LocalDateTime.now())
                .transactionReference("FEE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .build();
        transactionRepository.save(tx);

        doubleEntryService.createDoubleEntry(
                tx.getId(),
                account.getId(),
                FEE_INCOME_GL_ACCOUNT_ID,
                payload.amount(),
                tx.getNote(),
                "SYSTEM_LOANS"
        );

        log.info("  [LoanFee] Fee ${} deducted   new balance=${} txRef={}",
                payload.amount(), account.getBalance(), tx.getTransactionReference());

        return ResponseEntity.ok(Map.of(
                "message", "Fee deducted successfully",
                "accountId", payload.accountId(),
                "feeDeducted", payload.amount(),
                "newBalance", account.getBalance(),
                "transactionRef", tx.getTransactionReference()
        ));
    }

    public record FeeDeductionRequest(Long accountId, BigDecimal amount, String reason) {}
}