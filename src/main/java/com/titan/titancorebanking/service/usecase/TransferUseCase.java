package com.titan.titancorebanking.service.usecase;

import com.titan.riskengine.RiskCheckResponse;
import com.titan.titancorebanking.dto.request.TransactionRequest;
import com.titan.titancorebanking.enums.TransactionStatus;
import com.titan.titancorebanking.enums.TransactionType;
import com.titan.titancorebanking.exception.DailyLimitExceededException;
import com.titan.titancorebanking.exception.InsufficientBalanceException;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.TransactionRepository;
import com.titan.titancorebanking.service.*;
import com.titan.titancorebanking.security.SecurityAuthorizationHelper;
import com.titan.titancorebanking.service.fee.FeeStrategyFactory;
import com.titan.titancorebanking.service.validation.TransactionDomainValidator;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.SQLException;
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
public class TransferUseCase {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionAuditService auditService;
    private final FeeStrategyFactory feeStrategyFactory;
    private final RiskEngineGrpcService riskEngineGrpcService;
    private final AccountBucketService accountBucketService;
    private final IdempotencyService idempotencyService;
    private final TransferExecutionEngine executionEngine;
    private final TransactionDomainValidator validator;
    private final SecurityAuthorizationHelper securityHelper;

    @Value("${titan.business.timezone:Asia/Phnom_Penh}")
    private String businessTimezone;

    @Value("${titan.transfer.daily-limit:50000}")
    private BigDecimal dailyTransferLimit;

    public Transaction transfer(TransactionRequest request) {
        Authentication auth = securityHelper.requireAuthentication();
        return transfer(request, auth.getName());
    }

    public Transaction transfer(TransactionRequest request, String currentUsername) {
        securityHelper.verifyCallerIdentity(currentUsername);
        return executeTransfer(request, currentUsername, false);
    }

    public Transaction executeScheduledTransfer(TransactionRequest request, String ownerUsername) {
        securityHelper.verifyScheduledTransferAccess(ownerUsername);
        return executeTransfer(request, ownerUsername, true);
    }

    private Transaction executeTransfer(TransactionRequest request, String currentUsername, boolean isSystemCaller) {
        boolean generatedMdc = setupMdc("TRANSFER", currentUsername);
        final String uri = "/api/v1/transactions/transfer";
        boolean lockAcquired = false;

        try {
            validateTransferInput(request, isSystemCaller);

            // ── Step 1: Idempotency Fast Path with Atomic Reservation ──────────
            if (request.idempotencyKey() != null) {
                Optional<Transaction> existing = idempotencyService.getTransaction(request.idempotencyKey(), uri);
                if (existing.isPresent()) {
                    Transaction cached = existing.get();
                    executionEngine.validateTransferReplay(request, cached, currentUsername, isSystemCaller);
                    log.warn("Duplicate transfer request — returning cached result. key={}", request.idempotencyKey());
                    return cached;
                }

                lockAcquired = idempotencyService.acquireLock(request.idempotencyKey(), uri);
                if (!lockAcquired) {
                    Optional<Transaction> completed = idempotencyService.awaitResolutionOrAcquire(request.idempotencyKey(), uri);
                    if (completed.isPresent()) {
                        Transaction cached = completed.get();
                        executionEngine.validateTransferReplay(request, cached, currentUsername, isSystemCaller);
                        log.info("Concurrent transfer completed by peer — returning cached result. key={}", request.idempotencyKey());
                        return cached;
                    }
                    lockAcquired = true;
                }
            }

            // ── Step 2: Read accounts for pre-flight validation ────────────────
            Account rawFrom = accountRepository.findByAccountNumber(request.fromAccountNumber())
                    .orElseThrow(() -> new EntityNotFoundException("Source account not found: " + request.fromAccountNumber()));
            Account rawTo = accountRepository.findByAccountNumber(request.toAccountNumber())
                    .orElseThrow(() -> new EntityNotFoundException("Destination account not found: " + request.toAccountNumber()));

            if (rawFrom.getAccountNumber().equals(rawTo.getAccountNumber())) {
                throw new IllegalArgumentException("Cannot transfer to the same account.");
            }

            validator.requireActiveAccount(rawFrom);
            validator.requireActiveAccount(rawTo);

            securityHelper.verifyAccountOwnership(rawFrom, currentUsername, isSystemCaller ? "scheduled transfer" : "transfer");
            if (!isSystemCaller) {
                validator.validatePin(request.pin(), rawFrom);
            }

            BigDecimal fee = feeStrategyFactory.getStrategy(rawFrom.getUser().getTier()).calculateFee(request.amount());
            BigDecimal totalDeduction = request.amount().add(fee);

            // Pre-flight aggregated available funds check
            BigDecimal totalAggregated = accountBucketService != null
                    ? accountBucketService.calculateTotalAggregatedBalance(rawFrom.getId(), rawFrom.getBalance())
                    : null;
            if (totalAggregated == null) {
                totalAggregated = rawFrom.getBalance() != null ? rawFrom.getBalance() : BigDecimal.ZERO;
            }
            BigDecimal preflightEffectiveLimit = totalAggregated.add(validator.validateAndGetOverdraftLimit(rawFrom));
            if (preflightEffectiveLimit.compareTo(totalDeduction) < 0) {
                throw new InsufficientBalanceException(String.format(
                        "Insufficient balance. Required: %s (amount %s + fee %s). Available: %s",
                        totalDeduction, request.amount(), fee, preflightEffectiveLimit));
            }

            LocalDateTime startOfDayUtc = calculateStartOfDayUtc();
            BigDecimal sentToday = transactionRepository.sumOutboundTransfersSince(rawFrom.getId(), startOfDayUtc);
            if (sentToday.add(totalDeduction).compareTo(dailyTransferLimit) > 0) {
                throw new DailyLimitExceededException(String.format(
                        "Daily transfer limit of %s exceeded. Sent today: %s, Requested: %s",
                        dailyTransferLimit, sentToday, totalDeduction));
            }

            // ── Step 3: Remote gRPC Risk Check (OUTSIDE DB LEASE, executed ONCE) ──
            RiskCheckResponse riskResponse = riskEngineGrpcService.analyzeTransaction(
                    rawFrom.getUser().getId().toString(),
                    request.amount().doubleValue()
            );

            if ("BLOCK".equalsIgnoreCase(riskResponse.getAction())) {
                log.warn("Transfer BLOCKED by AI Risk Engine. score={} level={} from={} amount={}",
                        riskResponse.getRiskScore(), riskResponse.getRiskLevel(),
                        rawFrom.getAccountNumber(), request.amount());
                auditService.saveAuditLog(
                        rawFrom, rawTo, request.amount(), BigDecimal.ZERO,
                        TransactionType.TRANSFER, TransactionStatus.BLOCKED,
                        "Blocked by Risk Engine: " + sanitizeAuditNote(new IllegalStateException(riskResponse.getRiskLevel())));
                throw new SecurityException("Transaction blocked by Risk Engine: " + riskResponse.getRiskLevel());
            }

            // ── Step 4: Idempotent bucket pre-initialization ───────────────────
            accountBucketService.getOrInitializeBuckets(rawTo);

            // ── Step 5: Execute database transaction with @Retry(name = "db") ──
            return executionEngine.executeSecureTransferUnderLock(
                    request, currentUsername, rawFrom.getId(), rawTo.getId(),
                    fee, totalDeduction, startOfDayUtc, dailyTransferLimit, isSystemCaller);

        } catch (Exception e) {
            if (!isTransientDatabaseException(e)) {
                try {
                    Account from = request.fromAccountNumber() != null
                            ? accountRepository.findByAccountNumber(request.fromAccountNumber()).orElse(null)
                            : null;
                    Account to = request.toAccountNumber() != null
                            ? accountRepository.findByAccountNumber(request.toAccountNumber()).orElse(null)
                            : null;
                    if (from != null || to != null) {
                        auditService.saveAuditLog(
                                from, to,
                                request.amount() != null ? request.amount() : BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                TransactionType.TRANSFER, TransactionStatus.FAILED,
                                "Failed: " + sanitizeAuditNote(e));
                    }
                } catch (Exception auditEx) {
                    log.error("Failed to persist pre-flight FAILED transfer audit record: {}", auditEx.getMessage());
                }
            }
            throw e;
        } finally {
            if (lockAcquired && request.idempotencyKey() != null) {
                idempotencyService.releaseLock(request.idempotencyKey(), uri);
            }
            clearMdc(generatedMdc);
        }
    }

    private void validateTransferInput(TransactionRequest request, boolean isSystemCaller) {
        if (request == null) {
            throw new IllegalArgumentException("Transaction request cannot be null.");
        }
        if (request.fromAccountNumber() == null || request.fromAccountNumber().isBlank()) {
            throw new IllegalArgumentException("Source account number is required.");
        }
        if (request.toAccountNumber() == null || request.toAccountNumber().isBlank()) {
            throw new IllegalArgumentException("Destination account number is required.");
        }
        validator.validateMonetaryAmount(request.amount(), "Transfer");
        if (!isSystemCaller && (request.pin() == null || request.pin().isBlank())) {
            throw new com.titan.titancorebanking.exception.InvalidPinException("PIN is required for user transfers.");
        }
    }

    private boolean isTransientDatabaseException(Throwable t) {
        Throwable current = t;
        while (current != null) {
            if (current instanceof TransientDataAccessException) return true;
            if (current instanceof org.springframework.dao.PessimisticLockingFailureException) return true;
            if (current instanceof org.springframework.dao.CannotAcquireLockException) return true;
            if (current instanceof SQLException sqlEx) {
                String state = sqlEx.getSQLState();
                if ("40001".equals(state) || "40P01".equals(state)) return true;
            }
            current = current.getCause();
        }
        return false;
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
