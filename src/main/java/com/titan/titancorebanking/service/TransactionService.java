package com.titan.titancorebanking.service;

import com.titan.titancorebanking.dto.request.TransactionRequest;
import com.titan.titancorebanking.dto.response.TransactionResponse;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.enums.TransactionStatus;
import com.titan.titancorebanking.enums.TransactionType;
import com.titan.titancorebanking.failsafe.DeadMansSwitchService;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.TransactionRepository;
import com.titan.titancorebanking.service.imple.ExchangeRateService;
import com.titan.titancorebanking.exception.InsufficientBalanceException;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * JAVA 21 MODERNIZED: Enterprise Transaction Service
 * - Enforces Single Responsibility (All transaction logic centralized here)
 * - Strict Domain Exceptions over generic RuntimeExceptions
 * - Implements SLF4J MDC for distributed observability
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {

    // REFACTOR: Extracted magic strings to immutable constants to prevent silent drift[cite: 1]
    private static final String IDEMPOTENCY_SCOPE_TRANSFER = "/api/v1/transactions/transfer";
    private static final String IDEMPOTENCY_SCOPE_WITHDRAW = "/api/v1/transactions/withdraw";
    private static final String IDEMPOTENCY_SCOPE_DEPOSIT = "/api/v1/transactions/deposit";
    private static final String DEFAULT_PIN = "0000";
    private static final String TRACE_ID_KEY = "transactionTraceId";

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final PasswordEncoder passwordEncoder;
    private final ExchangeRateService exchangeRateService;
    private final TransactionAuditService auditService;
    private final EventPublisherService eventPublisherService;
    private final DoubleEntryService doubleEntryService;
    private final IdempotencyService idempotencyService;
    private final RiskEngineGrpcService riskEngineGrpcService;
    private final DeadMansSwitchService deadMansSwitchService;
    private final AccountBucketService accountBucketService;

    // ==================================================================================
    //   1. TRANSFER (SECURE ENTERPRISE LOGIC)
    // ==================================================================================
    @Transactional
    @Retry(name = "db")
    public Transaction transfer(TransactionRequest request, String currentUsername) {
        // REFACTOR: Guard clauses extracted to maintain high cohesion in execution block
        validateSystemStateAndInput(request);

        if (request.idempotencyKey() != null) {
            Optional<Transaction> existing = idempotencyService.getTransaction(
                    request.idempotencyKey(),
                    IDEMPOTENCY_SCOPE_TRANSFER
            );
            if (existing.isPresent()) {
                log.warn("Duplicate transfer request intercepted by idempotency guard: {}", request.idempotencyKey());
                return existing.get();
            }
        }

        try {
            // REFACTOR: Inject trace ID into logging context for cross-service observability
            MDC.put(TRACE_ID_KEY, request.idempotencyKey() != null ? request.idempotencyKey() : "REQ-" + System.currentTimeMillis());
            return executeSecureTransfer(request, currentUsername);
        } finally {
            MDC.remove(TRACE_ID_KEY);
        }
    }

    protected Transaction executeSecureTransfer(TransactionRequest request, String currentUsername) {
        // REFACTOR: Replaced generic RuntimeException with standard EntityNotFoundException[cite: 1]
        Account rawFrom = accountRepository.findByAccountNumber(request.fromAccountNumber())
                .orElseThrow(() -> new EntityNotFoundException("Source account not found: " + request.fromAccountNumber()));
        Account rawTo = accountRepository.findByAccountNumber(request.toAccountNumber())
                .orElseThrow(() -> new EntityNotFoundException("Destination account not found: " + request.toAccountNumber()));

        if (rawFrom.getId().equals(rawTo.getId())) {
            throw new IllegalArgumentException("Source and destination accounts must be distinct entities.");
        }

        // --- RISK ENGINE CHECK ---
        var riskResponse = riskEngineGrpcService.analyzeTransaction(
                rawFrom.getUser().getId().toString(),
                request.amount().doubleValue()
        );

        if ("BLOCK".equalsIgnoreCase(riskResponse.getAction())) {
            log.warn("Transaction BLOCKED by AI Risk Engine: Score={}", riskResponse.getRiskScore());
            return auditService.saveAuditLog(rawFrom, rawTo, request.amount(),
                    TransactionType.TRANSFER, TransactionStatus.BLOCKED,
                    "Blocked by Risk Engine: " + riskResponse.getRiskLevel());
        }

        // --- DETERMINISTIC LOCK ORDERING ---
        // REFACTOR: Extracted locking logic to isolated method for reuse and clarity
        var lockedAccounts = acquireDeterministicLocks(rawFrom, rawTo);
        Account fromAccount = lockedAccounts.from();
        Account toAccount = lockedAccounts.to();

        // --- AUTHENTICATION/VALIDATION UNDER LOCK ---
        validateOwnershipAndPin(fromAccount, currentUsername, request.pin());

        try {
            BigDecimal fee = calculateFee(fromAccount);
            BigDecimal totalDeduction = request.amount().add(fee);

            if (fromAccount.getBalance().compareTo(totalDeduction) < 0) {
                // REFACTOR: Use strongly-typed domain exception[cite: 1]
                throw new InsufficientBalanceException("Insufficient Funds for transfer and applicable fees.");
            }

            // --- FX & BALANCE UPDATES ---
            BigDecimal targetAmount = request.amount();
            String note = request.note();

            if (fromAccount.getCurrency() != toAccount.getCurrency()) {
                targetAmount = exchangeRateService.convert(request.amount(), fromAccount.getCurrency(), toAccount.getCurrency());
                note += String.format(" [FX: %s -> %s]", fromAccount.getCurrency(), toAccount.getCurrency());
            }

            fromAccount.setBalance(fromAccount.getBalance().subtract(totalDeduction));
            accountRepository.save(fromAccount);

            int bucketIndex = accountBucketService.selectRandomBucketIndex();
            try {
                accountBucketService.creditBucket(toAccount.getId(), bucketIndex, targetAmount);
            } catch (Exception e) {
                log.warn("Bucket partition credit failed, falling back to primary row lock for account: {}", toAccount.getId());
                toAccount.setBalance(toAccount.getBalance().add(targetAmount));
                accountRepository.save(toAccount);
            }

            // --- POST-EXECUTION RECORDING ---
            Transaction tx = auditService.saveAuditLog(fromAccount, toAccount, request.amount(),
                    TransactionType.TRANSFER, TransactionStatus.SUCCESS, note);

            if (request.idempotencyKey() != null) {
                tx.setIdempotencyKey(request.idempotencyKey());
                transactionRepository.save(tx);
                idempotencyService.cacheTransaction(request.idempotencyKey(), IDEMPOTENCY_SCOPE_TRANSFER, tx);
            }

            doubleEntryService.createDoubleEntry(tx.getId(), fromAccount.getId(), toAccount.getId(), request.amount(), note, currentUsername);
            eventPublisherService.publishTransactionCompletedEvent(tx);

            return tx;

        } catch (Exception e) {
            log.error("Transfer execution failed, rolling back...", e);
            auditService.saveAuditLog(fromAccount, toAccount, request.amount(),
                    TransactionType.TRANSFER, TransactionStatus.FAILED, "System Fault: " + e.getMessage());
            throw e;
        }
    }

    // ==================================================================================
    //   2. WITHDRAWAL (SECURE)
    // ==================================================================================
    @Transactional
    public Transaction withdraw(TransactionRequest request, String currentUsername) {
        if (request.idempotencyKey() != null) {
            var existing = idempotencyService.getTransaction(request.idempotencyKey(), IDEMPOTENCY_SCOPE_WITHDRAW);
            if (existing.isPresent()) return existing.get();
        }

        Account account = fetchWithLock(request.fromAccountNumber());
        validateOwnershipAndPin(account, currentUsername, request.pin());

        try {
            if (account.getBalance().compareTo(request.amount()) < 0) {
                throw new InsufficientBalanceException("Insufficient funds for withdrawal.");
            }

            account.setBalance(account.getBalance().subtract(request.amount()));
            accountRepository.save(account);

            Transaction tx = auditService.saveAuditLog(account, null, request.amount(),
                    TransactionType.WITHDRAWAL, TransactionStatus.SUCCESS, "Withdrawal");

            if (request.idempotencyKey() != null) {
                tx.setIdempotencyKey(request.idempotencyKey());
                transactionRepository.save(tx);
                idempotencyService.cacheTransaction(request.idempotencyKey(), IDEMPOTENCY_SCOPE_WITHDRAW, tx);
            }

            eventPublisherService.publishTransactionCompletedEvent(tx);
            return tx;
        } catch (Exception e) {
            auditService.saveAuditLog(account, null, request.amount(),
                    TransactionType.WITHDRAWAL, TransactionStatus.FAILED, e.getMessage());
            throw e;
        }
    }

    // ==================================================================================
    //   3. DEPOSIT (SECURE)
    // ==================================================================================
    @Transactional
    public Transaction deposit(TransactionRequest request) {
        if (request.idempotencyKey() != null) {
            var existing = idempotencyService.getTransaction(request.idempotencyKey(), IDEMPOTENCY_SCOPE_DEPOSIT);
            if (existing.isPresent()) return existing.get();
        }

        String targetAccNum = request.toAccountNumber() != null ? request.toAccountNumber() : request.fromAccountNumber();
        Account account = fetchWithLock(targetAccNum);

        account.setBalance(account.getBalance().add(request.amount()));
        accountRepository.save(account);

        Transaction tx = auditService.saveAuditLog(null, account, request.amount(),
                TransactionType.DEPOSIT, TransactionStatus.SUCCESS, "Deposit");

        if (request.idempotencyKey() != null) {
            tx.setIdempotencyKey(request.idempotencyKey());
            transactionRepository.save(tx);
            idempotencyService.cacheTransaction(request.idempotencyKey(), IDEMPOTENCY_SCOPE_DEPOSIT, tx);
        }

        eventPublisherService.publishTransactionCompletedEvent(tx);
        return tx;
    }

    // ==================================================================================
    //   HELPER METHODS
    // ==================================================================================

    // REFACTOR: Centralized system state validation
    private void validateSystemStateAndInput(TransactionRequest request) {
        if (deadMansSwitchService.isLockdownActive()) {
            throw new IllegalStateException("System is in LOCKDOWN. All transactions are frozen.");
        }
        if (request.fromAccountNumber() == null || request.toAccountNumber() == null) {
            throw new IllegalArgumentException("Source and destination account numbers are required.");
        }
        if (request.fromAccountNumber().trim().equalsIgnoreCase(request.toAccountNumber().trim())) {
            throw new IllegalArgumentException("Cannot transfer funds to the same account.");
        }
        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Transfer amount must be strictly greater than zero.");
        }
    }

    // REFACTOR: Extracted deterministic locking to isolate infrastructure mechanics from business logic
    private LockedAccounts acquireDeterministicLocks(Account rawFrom, Account rawTo) {
        Long firstLockId = Math.min(rawFrom.getId(), rawTo.getId());
        Long secondLockId = Math.max(rawFrom.getId(), rawTo.getId());

        log.debug("Acquiring deterministic row locks: ID {} then ID {}", firstLockId, secondLockId);

        Account firstLocked = accountRepository.findByIdWithLock(firstLockId)
                .orElseThrow(() -> new EntityNotFoundException("Account lock acquisition failed for ID: " + firstLockId));
        Account secondLocked = accountRepository.findByIdWithLock(secondLockId)
                .orElseThrow(() -> new EntityNotFoundException("Account lock acquisition failed for ID: " + secondLockId));

        Account fromAccount = firstLocked.getId().equals(rawFrom.getId()) ? firstLocked : secondLocked;
        Account toAccount = firstLocked.getId().equals(rawTo.getId()) ? firstLocked : secondLocked;

        return new LockedAccounts(fromAccount, toAccount);
    }

    private record LockedAccounts(Account from, Account to) {}

    // REFACTOR: Centralized auth check to prevent Security bypassing
    private void validateOwnershipAndPin(Account account, String username, String requestedPin) {
        if (!account.getUser().getUsername().equals(username)) {
            throw new org.springframework.security.access.AccessDeniedException("Ownership verification failed for the requested account.");
        }
        String pin = requestedPin != null ? requestedPin : DEFAULT_PIN;
        if (!passwordEncoder.matches(pin, account.getUser().getPin())) {
            throw new org.springframework.security.access.AccessDeniedException("Cryptographic PIN verification failed.");
        }
    }

    private Account fetchWithLock(String accNum) {
        return accountRepository.findByAccountNumberWithLock(accNum)
                .orElseThrow(() -> new EntityNotFoundException("Account not found: " + accNum));
    }

    private BigDecimal calculateFee(Account account) {
        return switch (account.getAccountType()) {
            case SAVINGS -> account.getBalance().compareTo(new BigDecimal("10000")) >= 0
                    ? BigDecimal.ZERO
                    : new BigDecimal("0.50");
            case CHECKING -> new BigDecimal("1.00");
            case FIXED_DEPOSIT -> new BigDecimal("0.25");
            case LOAN -> account.getBalance().multiply(new BigDecimal("0.005"));
            default -> new BigDecimal("2.00");
        };
    }

    public List<TransactionResponse> getTransactionHistory(String username) {
        List<Transaction> transactions = transactionRepository.findAllByUser(username);
        return transactions.stream()
                .map(this::toTransactionResponse)
                .toList();
    }

    public TransactionResponse getLastTransaction(String username) {
        List<Transaction> transactions = transactionRepository.findAllByUser(username);
        if (transactions.isEmpty()) {
            throw new EntityNotFoundException("No transaction history found for user.");
        }
        return toTransactionResponse(transactions.getLast());
    }

    private TransactionResponse toTransactionResponse(Transaction transaction) {
        String currency = transaction.getFromAccount() != null && transaction.getFromAccount().getCurrency() != null
                ? transaction.getFromAccount().getCurrency().name()
                : (transaction.getToAccount() != null && transaction.getToAccount().getCurrency() != null
                ? transaction.getToAccount().getCurrency().name()
                : "USD");

        return new TransactionResponse(
                transaction.getId(),
                transaction.getTransactionType().name(),
                transaction.getAmount(),
                transaction.getFromAccount() != null ? transaction.getFromAccount().getAccountNumber() : null,
                transaction.getToAccount() != null ? transaction.getToAccount().getAccountNumber() : null,
                transaction.getStatus().name(),
                transaction.getNote(),
                transaction.getTimestamp(),
                currency,
                BigDecimal.ZERO,
                transaction.getIdempotencyKey()
        );
    }
}