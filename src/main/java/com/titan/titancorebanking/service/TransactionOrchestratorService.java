package com.titan.titancorebanking.service;

import com.titan.titancorebanking.dto.request.TransactionRequest;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.enums.TransactionStatus;
import com.titan.titancorebanking.enums.TransactionType;
import com.titan.titancorebanking.exception.RiskBlockedException;
import com.titan.titancorebanking.validator.TransactionValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Orchestrates transactions by handling Network I/O and Cryptography BEFORE acquiring DB locks.
 * Hardened with strict SRP, Thread-Safety, and clean architectural boundaries.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionOrchestratorService {

    private final RiskEngineGrpcService riskEngineGrpcService;
    private final TransactionExecutionService executionService;
    private final TransactionAuditService auditService;
    private final TransactionValidator transactionValidator;

    // Notice: Lockdown and Idempotency are now handled via @Idempotent and @SystemGuarded (AOP)
    // This prevents Check-Then-Act race conditions atomically at the entry point.

    @SystemGuarded
    @Idempotent(action = "TRANSFER")
    public Transaction transfer(TransactionRequest request, String currentUsername) {
        transactionValidator.validateTransferInput(request);

        // 1. Unlocked Read & Cryptographic Validation (Delegated for SRP)
        Account rawFrom = transactionValidator.getValidatedAccountAndVerifyOwnership(
                request.fromAccountNumber(), request.pin(), currentUsername);
        Account rawTo = transactionValidator.getValidatedDestinationAccount(request.toAccountNumber(), rawFrom);

        // 2. gRPC Network Call (Slow operation, outside DB locks)
        evaluateRiskOrThrow(rawFrom, rawTo, request);

        // 3. Delegate to strict Execution layer for ACID compliance
        return executionService.executeTransferAtomically(request, rawFrom.getId(), rawTo.getId(), currentUsername);
    }

    @SystemGuarded
    @Idempotent(action = "WITHDRAWAL")
    public Transaction withdraw(TransactionRequest request, String currentUsername) {
        transactionValidator.validateWithdrawalInput(request);

        Account account = transactionValidator.getValidatedAccountAndVerifyOwnership(
                request.fromAccountNumber(), request.pin(), currentUsername);

        return executionService.executeWithdrawalAtomically(request, account.getId(), currentUsername);
    }

    private void evaluateRiskOrThrow(Account from, Account to, TransactionRequest request) {
        var riskResponse = riskEngineGrpcService.analyzeTransaction(
                from.getUser().getId().toString(),
                request.amount().doubleValue()
        );

        if ("BLOCK".equalsIgnoreCase(riskResponse.getAction())) {
            log.warn("🚫 Transaction BLOCKED by AI Risk Engine: Score={}", riskResponse.getRiskScore());
            auditService.saveAuditLog(from, to, request.amount(),
                    TransactionType.TRANSFER, TransactionStatus.BLOCKED,
                    "Blocked by Risk Engine: " + riskResponse.getRiskLevel());

            throw new RiskBlockedException("Transaction declined by security policies.");
        }
    }
}