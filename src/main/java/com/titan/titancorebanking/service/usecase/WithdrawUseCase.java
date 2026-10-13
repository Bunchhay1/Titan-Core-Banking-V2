package com.titan.titancorebanking.service.usecase;

import com.titan.titancorebanking.dto.request.TransactionRequest;
import com.titan.titancorebanking.enums.TransactionStatus;
import com.titan.titancorebanking.enums.TransactionType;
import com.titan.titancorebanking.exception.InvalidPinException;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.service.IdempotencyService;
import com.titan.titancorebanking.service.TransactionAuditService;
import com.titan.titancorebanking.service.validation.TransactionDomainValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WithdrawUseCase {

    private final AccountRepository accountRepository;
    private final TransactionAuditService auditService;
    private final IdempotencyService idempotencyService;
    private final WithdrawExecutionEngine executionEngine;
    private final TransactionDomainValidator validator;
    private final com.titan.titancorebanking.security.SecurityAuthorizationHelper securityHelper;

    @Value("${titan.business.timezone:Asia/Phnom_Penh}")
    private String businessTimezone;

    @Value("${titan.withdraw.daily-limit:50000}")
    private BigDecimal dailyWithdrawLimit;

    public Transaction withdraw(TransactionRequest request) {
        Authentication auth = securityHelper.requireAuthentication();
        return withdraw(request, auth.getName());
    }

    public Transaction withdraw(TransactionRequest request, String currentUsername) {
        securityHelper.verifyCallerIdentity(currentUsername);
        return executeWithdraw(request, currentUsername);
    }

    private Transaction executeWithdraw(TransactionRequest request, String currentUsername) {
        boolean generatedMdc = setupMdc("WITHDRAW", currentUsername);
        final String uri = "/api/v1/transactions/withdraw";
        boolean lockAcquired = false;

        try {
            validateWithdrawInput(request);

            // ── Idempotency Fast Path with Atomic Reservation ──────────
            if (request.idempotencyKey() != null) {
                Optional<Transaction> existing = idempotencyService.getTransaction(request.idempotencyKey(), uri);
                if (existing.isPresent()) {
                    Transaction cached = existing.get();
                    executionEngine.validateWithdrawReplay(request, cached, currentUsername);
                    log.warn("Duplicate withdraw request — returning cached result. key={}", request.idempotencyKey());
                    return cached;
                }

                lockAcquired = idempotencyService.acquireLock(request.idempotencyKey(), uri);
                if (!lockAcquired) {
                    Optional<Transaction> completed = idempotencyService.awaitResolutionOrAcquire(request.idempotencyKey(), uri);
                    if (completed.isPresent()) {
                        Transaction cached = completed.get();
                        executionEngine.validateWithdrawReplay(request, cached, currentUsername);
                        log.info("Concurrent withdraw completed by peer — returning cached result. key={}", request.idempotencyKey());
                        return cached;
                    }
                    lockAcquired = true;
                }
            }

            LocalDateTime startOfDayUtc = calculateStartOfDayUtc();

            // Delegate to database execution engine with isolated transaction and retry
            return executionEngine.executeSecureWithdrawUnderLock(
                    request, currentUsername, startOfDayUtc, dailyWithdrawLimit);

        } catch (Exception e) {
            try {
                if (request.fromAccountNumber() != null) {
                    accountRepository.findByAccountNumber(request.fromAccountNumber())
                            .ifPresent(acct -> auditService.saveAuditLog(
                                    acct, null,
                                    request.amount() != null ? request.amount() : BigDecimal.ZERO,
                                    BigDecimal.ZERO,
                                    TransactionType.WITHDRAWAL, TransactionStatus.FAILED,
                                    "Failed: " + sanitizeAuditNote(e)));
                }
            } catch (Exception auditEx) {
                log.error("Failed to persist FAILED withdraw audit record: {}", auditEx.getMessage());
            }
            throw e;
        } finally {
            if (lockAcquired && request.idempotencyKey() != null) {
                idempotencyService.releaseLock(request.idempotencyKey(), uri);
            }
            clearMdc(generatedMdc);
        }
    }

    private void validateWithdrawInput(TransactionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Transaction request cannot be null.");
        }
        if (request.fromAccountNumber() == null || request.fromAccountNumber().isBlank()) {
            throw new IllegalArgumentException("Source account number is required for withdrawal.");
        }
        validator.validateMonetaryAmount(request.amount(), "Withdrawal");
        if (request.pin() == null || request.pin().isBlank()) {
            throw new InvalidPinException("PIN is required for withdrawal.");
        }
    }

    private LocalDateTime calculateStartOfDayUtc() {
        ZoneId businessZone = ZoneId.of(businessTimezone);
        ZonedDateTime localMidnight = LocalDate.now(businessZone).atStartOfDay(businessZone);
        return localMidnight.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
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
