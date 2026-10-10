package com.titan.titancorebanking.service;

import com.titan.titancorebanking.dto.request.TransactionRequest;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.enums.TransactionStatus;
import com.titan.titancorebanking.enums.TransactionType;
import com.titan.titancorebanking.failsafe.DeadMansSwitchService;
import com.titan.titancorebanking.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final RiskEngineGrpcService riskEngineGrpcService;
    private final IdempotencyService idempotencyService;
    private final DeadMansSwitchService deadMansSwitchService;
    private final TransactionExecutionService executionService;
    private final TransactionAuditService auditService;

    public Transaction transfer(TransactionRequest request, String currentUsername) {
        if (deadMansSwitchService.isLockdownActive()) {
            throw new IllegalStateException("System is in LOCKDOWN. All transactions are frozen.");
        }

        validateTransferRequest(request);

        if (request.idempotencyKey() != null) {
            return idempotencyService.getTransaction(request.idempotencyKey(), "/api/v1/transactions/transfer")
                    .orElseGet(() -> processTransfer(request, currentUsername));
        }

        return processTransfer(request, currentUsername);
    }

    public Transaction withdraw(TransactionRequest request, String currentUsername) {
        if (deadMansSwitchService.isLockdownActive()) {
            throw new IllegalStateException("System is in LOCKDOWN.");
        }

        if (request.idempotencyKey() != null) {
            return idempotencyService.getTransaction(request.idempotencyKey(), "/api/v1/transactions/withdraw")
                    .orElseGet(() -> processWithdrawal(request, currentUsername));
        }

        return processWithdrawal(request, currentUsername);
    }

    private Transaction processTransfer(TransactionRequest request, String currentUsername) {
        Account fromAccount = accountRepository.findByAccountNumber(request.fromAccountNumber())
                .orElseThrow(() -> new IllegalArgumentException("Source account not found."));
        Account toAccount = accountRepository.findByAccountNumber(request.toAccountNumber())
                .orElseThrow(() -> new IllegalArgumentException("Destination account not found."));

        validateOwnershipAndSecurity(fromAccount, request.pin(), currentUsername);

        var riskResponse = riskEngineGrpcService.analyzeTransaction(
                fromAccount.getUser().getId().toString(),
                request.amount().doubleValue()
        );

        if ("BLOCK".equalsIgnoreCase(riskResponse.getAction())) {
            log.warn("Transaction BLOCKED by AI Risk Engine: Score={}", riskResponse.getRiskScore());
            return auditService.saveAuditLog(fromAccount, toAccount, request.amount(),
                    TransactionType.TRANSFER, TransactionStatus.BLOCKED,
                    "Blocked by Risk Engine: " + riskResponse.getRiskLevel());
        }

        return executionService.executeTransferAtomically(request, fromAccount.getId(), toAccount.getId(), currentUsername);
    }

    private Transaction processWithdrawal(TransactionRequest request, String currentUsername) {
        Account account = accountRepository.findByAccountNumber(request.fromAccountNumber())
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));

        validateOwnershipAndSecurity(account, request.pin(), currentUsername);

        var riskResponse = riskEngineGrpcService.analyzeTransaction(
                account.getUser().getId().toString(),
                request.amount().doubleValue()
        );

        if ("BLOCK".equalsIgnoreCase(riskResponse.getAction())) {
            log.warn("Withdrawal BLOCKED by AI Risk Engine: Score={}", riskResponse.getRiskScore());
            return auditService.saveAuditLog(account, null, request.amount(),
                    TransactionType.WITHDRAWAL, TransactionStatus.BLOCKED,
                    "Blocked by Risk Engine");
        }

        return executionService.executeWithdrawalAtomically(request, account.getId(), currentUsername);
    }

    private void validateOwnershipAndSecurity(Account account, String providedPin, String currentUsername) {
        if (!account.getUser().getUsername().equals(currentUsername)) {
            throw new SecurityException("Ownership validation failed.");
        }

        if (providedPin == null || providedPin.isBlank()) {
            throw new SecurityException("PIN is required for authorization.");
        }

        if (!passwordEncoder.matches(providedPin, account.getUser().getPin())) {
            throw new SecurityException("Invalid PIN");
        }
    }

    private void validateTransferRequest(TransactionRequest request) {
        if (request.fromAccountNumber() == null || request.fromAccountNumber().equals(request.toAccountNumber())) {
            throw new IllegalArgumentException("Invalid source or destination accounts.");
        }
        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Transfer amount must be strictly positive.");
        }
    }
}