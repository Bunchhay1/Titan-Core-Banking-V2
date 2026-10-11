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
 * Internal Loan Disbursement Controller.
 * @Hidden from Swagger UI. Ensures atomic processing with DoubleEntryService integration.
 * In a production environment, this must be protected by an API Gateway routing rule
 * or Istio mTLS policy preventing external ingress.
 */
@Hidden
@RestController
@RequestMapping("/api/v1/transactions/internal")
@RequiredArgsConstructor
@Slf4j
public class LoanDisbursementController {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final DoubleEntryService doubleEntryService;

    // Assuming ID 4 represents the Bank's Loan Disbursement Asset GL Account
    private static final Long LOAN_DISBURSEMENT_GL_ACCOUNT_ID = 4L;

    @PostMapping("/disburse-loan")
    @Transactional
    public ResponseEntity<Map<String, Object>> disburseLoan(@RequestBody DisbursementRequest payload) {
        log.info("  [LoanDisburse] Crediting ${} to account {}   reason: {}",
                payload.amount(), payload.accountId(), payload.reason());

        Account account = accountRepository.findByIdWithLock(payload.accountId())
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + payload.accountId()));

        account.setBalance(account.getBalance().add(payload.amount()));
        accountRepository.save(account);

        Transaction tx = Transaction.builder()
                .toAccount(account)
                .transactionType(TransactionType.LOAN_DISBURSEMENT)
                .amount(payload.amount())
                .status(TransactionStatus.SUCCESS)
                .note(payload.reason() != null ? payload.reason() : "Loan disbursement")
                .timestamp(LocalDateTime.now())
                .transactionReference("LOAN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .build();
        transactionRepository.save(tx);

        doubleEntryService.createDoubleEntry(
                tx.getId(),
                LOAN_DISBURSEMENT_GL_ACCOUNT_ID,
                account.getId(),
                payload.amount(),
                tx.getNote(),
                "SYSTEM_LOANS"
        );

        log.info("  [LoanDisburse] ${} credited   new balance=${} txRef={}",
                payload.amount(), account.getBalance(), tx.getTransactionReference());

        return ResponseEntity.ok(Map.of(
                "message", "Loan disbursed successfully",
                "accountId", payload.accountId(),
                "amountCredited", payload.amount(),
                "newBalance", account.getBalance(),
                "transactionRef", tx.getTransactionReference()
        ));
    }

    public record DisbursementRequest(Long accountId, BigDecimal amount, Long loanId, String reason) {}
}