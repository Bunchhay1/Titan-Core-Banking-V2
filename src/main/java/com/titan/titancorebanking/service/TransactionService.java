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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {

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

    @Transactional
    @Retry(name = "db")
    public Transaction transfer(TransactionRequest request, String currentUsername) {
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
            throw new IllegalArgumentException("Transfer amount must be greater than zero.");
        }

        if (request.idempotencyKey() != null) {
            Optional<Transaction> existing = idempotencyService.getTransaction(
                    request.idempotencyKey(),
                    "/api/v1/transactions/transfer"
            );
            if (existing.isPresent()) {
                log.warn("  Duplicate transfer request detected: {}", request.idempotencyKey());
                return existing.get();
            }
        }

        return executeSecureTransfer(request, currentUsername);
    }

    protected Transaction executeSecureTransfer(TransactionRequest request, String currentUsername) {
        // 1. Initial lookups to resolve Primary Key IDs (No locks yet)
        Account rawFrom = accountRepository.findByAccountNumber(request.fromAccountNumber())
                .orElseThrow(() -> new RuntimeException("Source account not found: " + request.fromAccountNumber()));
        Account rawTo = accountRepository.findByAccountNumber(request.toAccountNumber())
                .orElseThrow(() -> new RuntimeException("Destination account not found: " + request.toAccountNumber()));

        // --- PRE-FLIGHT RISK ENGINE CHECK (Before acquiring DB locks) ---
        var riskResponse = riskEngineGrpcService.analyzeTransaction(
                rawFrom.getUser().getId().toString(),
                request.amount().doubleValue()
        );

        if ("BLOCK".equalsIgnoreCase(riskResponse.getAction())) {
            log.warn("  Transaction BLOCKED by AI Risk Engine: Score={}", riskResponse.getRiskScore());
            return auditService.saveAuditLog(rawFrom, rawTo, request.amount(),
                    TransactionType.TRANSFER, TransactionStatus.BLOCKED,
                    "Blocked by Risk Engine: " + riskResponse.getRiskLevel());
        }

        // 2. DETERMINISTIC LOCK ORDERING (Always lock smaller ID first to prevent deadlocks)
        Long firstLockId = Math.min(rawFrom.getId(), rawTo.getId());
        Long secondLockId = Math.max(rawFrom.getId(), rawTo.getId());

        Account firstLocked = accountRepository.findByIdWithLock(firstLockId)
                .orElseThrow(() -> new RuntimeException("Account not found for locking: ID " + firstLockId));
        Account secondLocked = accountRepository.findByIdWithLock(secondLockId)
                .orElseThrow(() -> new RuntimeException("Account not found for locking: ID " + secondLockId));

        // Re-map locked accounts back to semantic roles
        Account fromAccount = firstLocked.getId().equals(rawFrom.getId()) ? firstLocked : secondLocked;
        Account toAccount = firstLocked.getId().equals(rawTo.getId()) ? firstLocked : secondLocked;

        // 3. TOCTOU Prevention: Validate ownership and PIN inside the lock boundary
        if (!fromAccount.getUser().getUsername().equals(currentUsername)) {
            throw new RuntimeException("  You do not own this account!");
        }

        String pin = request.pin() != null ? request.pin() : "0000";
        if (!passwordEncoder.matches(pin, fromAccount.getUser().getPin())) {
            throw new RuntimeException("  Invalid PIN");
        }

        try {
            BigDecimal fee = calculateFee(fromAccount);
            BigDecimal totalDeduction = request.amount().add(fee);

            if (fromAccount.getBalance().compareTo(totalDeduction) < 0) {
                throw new RuntimeException("  Insufficient Funds");
            }

            // --- FX CALCULATION ---
            BigDecimal targetAmount = request.amount();
            String note = request.note();
            if (fromAccount.getCurrency() != toAccount.getCurrency()) {
                targetAmount = exchangeRateService.convert(request.amount(), fromAccount.getCurrency(), toAccount.getCurrency());
                note += " [FX: " + fromAccount.getCurrency() + " -> " + toAccount.getCurrency() + "]";
            }

            // --- EXECUTION (Debit Sender Row, Credit Receiver Partitioned Bucket) ---
            fromAccount.setBalance(fromAccount.getBalance().subtract(totalDeduction));
            accountRepository.save(fromAccount);

            int bucketIndex = accountBucketService.selectRandomBucketIndex();
            try {
                accountBucketService.creditBucket(toAccount.getId(), bucketIndex, targetAmount);
            } catch (Exception e) {
                // Fallback to primary row if bucket fails
                toAccount.setBalance(toAccount.getBalance().add(targetAmount));
                accountRepository.save(toAccount);
            }

            Transaction tx = auditService.saveAuditLog(fromAccount, toAccount, request.amount(),
                    TransactionType.TRANSFER, TransactionStatus.SUCCESS, note);

            if (request.idempotencyKey() != null) {
                tx.setIdempotencyKey(request.idempotencyKey());
                transactionRepository.save(tx);
                idempotencyService.cacheTransaction(
                        request.idempotencyKey(),
                        "/api/v1/transactions/transfer",
                        tx
                );
            }

            // Create mandatory double-entry ledger records
            doubleEntryService.createDoubleEntry(
                    tx.getId(),
                    fromAccount.getId(),
                    toAccount.getId(),
                    request.amount(),
                    note,
                    currentUsername
            );

            // Publish Outbox Event for Guaranteed Delivery
            eventPublisherService.publishTransactionCompletedEvent(tx);

            return tx;

        } catch (Exception e) {
            auditService.saveAuditLog(fromAccount, toAccount, request.amount(),
                    TransactionType.TRANSFER, TransactionStatus.FAILED, "Failed: " + e.getMessage());
            throw e;
        }
    }

    @Transactional
    public Transaction withdraw(TransactionRequest request, String currentUsername) {
        if (request.idempotencyKey() != null) {
            Optional<Transaction> existing = idempotencyService.getTransaction(
                    request.idempotencyKey(),
                    "/api/v1/transactions/withdraw"
            );
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        // Acquire lock FIRST, then validate inside the lock boundary
        Account account = accountRepository.findByAccountNumberWithLock(request.fromAccountNumber())
                .orElseThrow(() -> new RuntimeException("Account not found: " + request.fromAccountNumber()));

        if (!account.getUser().getUsername().equals(currentUsername)) {
            throw new RuntimeException("  You do not own this account!");
        }

        String pin = request.pin() != null ? request.pin() : "0000";
        if (!passwordEncoder.matches(pin, account.getUser().getPin())) {
            throw new RuntimeException("  Invalid PIN");
        }

        try {
            if (account.getBalance().compareTo(request.amount()) < 0) {
                throw new RuntimeException("  Insufficient funds");
            }

            account.setBalance(account.getBalance().subtract(request.amount()));
            accountRepository.save(account);

            Transaction tx = auditService.saveAuditLog(account, null, request.amount(),
                    TransactionType.WITHDRAWAL, TransactionStatus.SUCCESS, "Withdrawal");

            if (request.idempotencyKey() != null) {
                tx.setIdempotencyKey(request.idempotencyKey());
                transactionRepository.save(tx);
                idempotencyService.cacheTransaction(
                        request.idempotencyKey(),
                        "/api/v1/transactions/withdraw",
                        tx
                );
            }

            eventPublisherService.publishTransactionCompletedEvent(tx);
            return tx;

        } catch (Exception e) {
            auditService.saveAuditLog(account, null, request.amount(),
                    TransactionType.WITHDRAWAL, TransactionStatus.FAILED, e.getMessage());
            throw e;
        }
    }

    @Transactional
    public Transaction deposit(TransactionRequest request) {
        if (request.idempotencyKey() != null) {
            Optional<Transaction> existing = idempotencyService.getTransaction(
                    request.idempotencyKey(),
                    "/api/v1/transactions/deposit"
            );
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        String targetAccNum = request.toAccountNumber() != null ? request.toAccountNumber() : request.fromAccountNumber();
        Account account = accountRepository.findByAccountNumberWithLock(targetAccNum)
                .orElseThrow(() -> new RuntimeException("Account not found: " + targetAccNum));

        account.setBalance(account.getBalance().add(request.amount()));
        accountRepository.save(account);

        Transaction tx = auditService.saveAuditLog(null, account, request.amount(),
                TransactionType.DEPOSIT, TransactionStatus.SUCCESS, "Deposit");

        if (request.idempotencyKey() != null) {
            tx.setIdempotencyKey(request.idempotencyKey());
            transactionRepository.save(tx);
            idempotencyService.cacheTransaction(
                    request.idempotencyKey(),
                    "/api/v1/transactions/deposit",
                    tx
            );
        }

        eventPublisherService.publishTransactionCompletedEvent(tx);
        return tx;
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
            throw new RuntimeException("No transactions found");
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