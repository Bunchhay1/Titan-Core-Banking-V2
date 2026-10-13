package com.titan.titancorebanking.service.usecase;

import com.titan.titancorebanking.dto.request.TransactionRequest;
import com.titan.titancorebanking.enums.TransactionStatus;
import com.titan.titancorebanking.enums.TransactionType;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.service.IdempotencyService;
import com.titan.titancorebanking.service.TransactionAuditService;
import com.titan.titancorebanking.service.validation.TransactionDomainValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DepositUseCase {

    private final AccountRepository accountRepository;
    private final TransactionAuditService auditService;
    private final IdempotencyService idempotencyService;
    private final DepositExecutionEngine executionEngine;
    private final TransactionDomainValidator validator;
    private final com.titan.titancorebanking.security.SecurityAuthorizationHelper securityHelper;

    public Transaction deposit(TransactionRequest request) {
        Authentication auth = securityHelper.requireAuthentication();
        return deposit(request, auth.getName());
    }

    public Transaction deposit(TransactionRequest request, String currentUsername) {
        securityHelper.verifyDepositCallerIdentity(currentUsername);
        return executeDeposit(request, currentUsername, false);
    }

    public Transaction depositInternal(TransactionRequest request, String internalCallerIdentity) {
        securityHelper.verifyInternalDepositAccess(internalCallerIdentity);
        return executeDeposit(request, internalCallerIdentity, true);
    }

    private Transaction executeDeposit(TransactionRequest request, String callerIdentity, boolean isTrustedInternal) {
        boolean generatedMdc = setupMdc("DEPOSIT", callerIdentity);
        final String uri = "/api/v1/transactions/deposit";
        boolean lockAcquired = false;

        final String targetAccNum = request.toAccountNumber() != null
                ? request.toAccountNumber()
                : request.fromAccountNumber();
        try {
            validateDepositInput(targetAccNum, request.amount());

            // ── Idempotency Fast Path with Atomic Reservation ──────────
            if (request.idempotencyKey() != null) {
                Optional<Transaction> existing = idempotencyService.getTransaction(request.idempotencyKey(), uri);
                if (existing.isPresent()) {
                    Transaction cached = existing.get();
                    executionEngine.validateDepositReplay(request, targetAccNum, cached, callerIdentity, isTrustedInternal);
                    log.warn("Duplicate deposit request — returning cached result. key={}", request.idempotencyKey());
                    return cached;
                }

                lockAcquired = idempotencyService.acquireLock(request.idempotencyKey(), uri);
                if (!lockAcquired) {
                    Optional<Transaction> completed = idempotencyService.awaitResolutionOrAcquire(request.idempotencyKey(), uri);
                    if (completed.isPresent()) {
                        Transaction cached = completed.get();
                        executionEngine.validateDepositReplay(request, targetAccNum, cached, callerIdentity, isTrustedInternal);
                        log.info("Concurrent deposit completed by peer — returning cached result. key={}", request.idempotencyKey());
                        return cached;
                    }
                    lockAcquired = true;
                }
            }

            // Delegate to database execution engine with isolated transaction and retry
            return executionEngine.executeSecureDepositUnderLock(
                    request, targetAccNum, callerIdentity, isTrustedInternal);

        } catch (Exception e) {
            try {
                if (targetAccNum != null) {
                    accountRepository.findByAccountNumber(targetAccNum)
                            .ifPresent(acct -> auditService.saveAuditLog(
                                    null, acct,
                                    request.amount() != null ? request.amount() : BigDecimal.ZERO,
                                    BigDecimal.ZERO,
                                    TransactionType.DEPOSIT, TransactionStatus.FAILED,
                                    "Failed: " + sanitizeAuditNote(e)));
                }
            } catch (Exception auditEx) {
                log.error("Failed to persist FAILED deposit audit record: {}", auditEx.getMessage());
            }
            throw e;
        } finally {
            if (lockAcquired && request.idempotencyKey() != null) {
                idempotencyService.releaseLock(request.idempotencyKey(), uri);
            }
            clearMdc(generatedMdc);
        }
    }

    private void validateDepositInput(String targetAccNum, BigDecimal amount) {
        if (targetAccNum == null || targetAccNum.isBlank()) {
            throw new IllegalArgumentException("Target account number is required for deposit.");
        }
        validator.validateMonetaryAmount(amount, "Deposit");
    }

    private String sanitizeAuditNote(Throwable t) {
        if (t == null) return "Unknown failure";
        String message = t.getMessage();
        if (message == null || message.isBlank()) {
            return t.getClass().getSimpleName();
        }
        String sanitized = message.replaceAll("(?i)(pin|password|secret|token|key)[=:\\s]+(\\S+)", "$1=***");
        if (sanitized.length() > 250) {
            sanitized = sanitized.substring(0, 247) + "...";
        }
        return sanitized;
    }

    private boolean setupMdc(String operation, String username) {
        boolean generatedCorrelationId = false;
        if (MDC.get("correlationId") == null) {
            MDC.put("correlationId", UUID.randomUUID().toString());
            generatedCorrelationId = true;
        }
        MDC.put("operation", operation);
        MDC.put("username", username != null ? username : "SYSTEM");
        return generatedCorrelationId;
    }

    private void clearMdc(boolean generatedCorrelationId) {
        if (generatedCorrelationId) {
            MDC.remove("correlationId");
        }
        MDC.remove("operation");
        MDC.remove("username");
        MDC.remove("accountId");
        MDC.remove("transactionId");
    }
}
