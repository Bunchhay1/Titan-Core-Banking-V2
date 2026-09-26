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

/**
 * ✅ JAVA 21 MODERNIZED: Transaction Service
 * - Uses record for DTOs
 * - Pattern matching switch for fee calculation
 * - Sequenced collections for history
 * - var for local variables
 */
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
    // NOTE: No NotificationService here. Core-banking publishes events to Kafka
    // via EventPublisherService → OutboxRelayService. titan-notifications-service
    // consumes those Kafka events and owns all push/alert delivery.

    // ==================================================================================
    // 💸 1. TRANSFER (SECURE ENTERPRISE LOGIC WITH DETERMINISTIC LOCK ORDERING & FAULT TOLERANCE)
    // ==================================================================================
    @Transactional
    @Retry(name = "db")
    public Transaction transfer(TransactionRequest request, String currentUsername) {
        // Task 10: Lockdown guard
        if (deadMansSwitchService.isLockdownActive()) {
            throw new IllegalStateException("System is in LOCKDOWN. All transactions are frozen.");
        }

        // Fault Tolerance: Input Validation
        if (request.fromAccountNumber() == null || request.toAccountNumber() == null) {
            throw new IllegalArgumentException("Source and destination account numbers are required.");
        }
        if (request.fromAccountNumber().trim().equalsIgnoreCase(request.toAccountNumber().trim())) {
            throw new IllegalArgumentException("Cannot transfer funds to the same account.");
        }
        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Transfer amount must be greater than zero.");
        }

        // ✅ FIX 3: Idempotency Check with URI
        if (request.idempotencyKey() != null) {
            var existing = idempotencyService.getTransaction(
                request.idempotencyKey(), 
                "/api/v1/transactions/transfer"
            );
            if (existing.isPresent()) {
                log.warn("⚠️ Duplicate request detected: {}", request.idempotencyKey());
                return existing.get();
            }
        }
        
        return executeSecureTransfer(request, currentUsername);
    }

    protected Transaction executeSecureTransfer(TransactionRequest request, String currentUsername) {
        // 1. Initial lookups to resolve Primary Key IDs
        Account rawFrom = accountRepository.findByAccountNumber(request.fromAccountNumber())
                .orElseThrow(() -> new RuntimeException("Source account not found: " + request.fromAccountNumber()));
        Account rawTo = accountRepository.findByAccountNumber(request.toAccountNumber())
                .orElseThrow(() -> new RuntimeException("Destination account not found: " + request.toAccountNumber()));

        if (rawFrom.getId().equals(rawTo.getId())) {
            throw new IllegalArgumentException("Source and destination accounts cannot have the same ID.");
        }

        // --- RISK ENGINE CHECK (Pre-flight before acquiring DB locks) ---
        var riskResponse = riskEngineGrpcService.analyzeTransaction(
                rawFrom.getUser().getId().toString(),
                request.amount().doubleValue()
        );

        if ("BLOCK".equalsIgnoreCase(riskResponse.getAction())) {
            log.warn("🚫 Transaction BLOCKED by AI Risk Engine: Score={}", riskResponse.getRiskScore());
            Transaction blockedTx = auditService.saveAuditLog(rawFrom, rawTo, request.amount(),
                    TransactionType.TRANSFER, TransactionStatus.BLOCKED,
                    "Blocked by Risk Engine: " + riskResponse.getRiskLevel());
            return blockedTx;
        }

        // 2. 🔥 DETERMINISTIC LOCK ORDERING (ID smaller first)
        Long firstLockId = Math.min(rawFrom.getId(), rawTo.getId());
        Long secondLockId = Math.max(rawFrom.getId(), rawTo.getId());

        log.debug("🔒 Deterministic locking order: acquiring lock for ID {} then ID {}", firstLockId, secondLockId);

        Account firstLocked = accountRepository.findByIdWithLock(firstLockId)
                .orElseThrow(() -> new RuntimeException("Account not found for locking: ID " + firstLockId));
        Account secondLocked = accountRepository.findByIdWithLock(secondLockId)
                .orElseThrow(() -> new RuntimeException("Account not found for locking: ID " + secondLockId));

        // Map locked accounts back to sender (fromAccount) and receiver (toAccount)
        Account fromAccount = firstLocked.getId().equals(rawFrom.getId()) ? firstLocked : secondLocked;
        Account toAccount = firstLocked.getId().equals(rawTo.getId()) ? firstLocked : secondLocked;

        // 3. Validate ownership and PIN on locked row (Prevents TOCTOU race conditions)
        if (!fromAccount.getUser().getUsername().equals(currentUsername)) {
            throw new RuntimeException("⛔ You do not own this account!");
        }
        String pin = request.pin() != null ? request.pin() : "0000";
        if (!passwordEncoder.matches(pin, fromAccount.getUser().getPin())) {
            throw new RuntimeException("❌ Invalid PIN");
        }

        try {
            
            // --- VALIDATION INSIDE LOCK ---
            BigDecimal fee = calculateFee(fromAccount);
            BigDecimal totalDeduction = request.amount().add(fee);

            if (fromAccount.getBalance().compareTo(totalDeduction) < 0) {
                throw new RuntimeException("❌ Insufficient Funds");
            }

            // --- FX CALCULATION ---
            BigDecimal targetAmount = request.amount();
            String note = request.note();
            if (fromAccount.getCurrency() != toAccount.getCurrency()) {
                targetAmount = exchangeRateService.convert(request.amount(), fromAccount.getCurrency(), toAccount.getCurrency());
                note += " [FX: " + fromAccount.getCurrency() + " -> " + toAccount.getCurrency() + "]";
            }

            // --- EXECUTION (Debit Sender, Credit Receiver Partitioned Bucket) ---
            fromAccount.setBalance(fromAccount.getBalance().subtract(totalDeduction));
            accountRepository.save(fromAccount);

            int bucketIndex = accountBucketService.selectRandomBucketIndex();
            try {
                accountBucketService.creditBucket(toAccount.getId(), bucketIndex, targetAmount);
            } catch (Exception e) {
                toAccount.setBalance(toAccount.getBalance().add(targetAmount));
                accountRepository.save(toAccount);
            }

            // ✅ FIX 1: Save transaction with idempotency key
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

            // ✅ FIX 2: Create double-entry ledger entries
            doubleEntryService.createDoubleEntry(
                tx.getId(),
                fromAccount.getId(),
                toAccount.getId(),
                request.amount(),
                note,
                currentUsername
            );

            // ✅ FIX 3: Publish to outbox (SAME transaction!)
            // OutboxRelayService polls every 2 s and forwards to Kafka.
            // titan-notifications-service consumes the Kafka event and sends the push.
            eventPublisherService.publishTransactionCompletedEvent(tx);

            return tx;

        } catch (Exception e) {
            // 🛑 Log Failure
            auditService.saveAuditLog(fromAccount, toAccount, request.amount(),
                    TransactionType.TRANSFER, TransactionStatus.FAILED, "Failed: " + e.getMessage());
            throw e;
        }
    }

    // ==================================================================================
    // 🏧 2. WITHDRAWAL (SECURE)
    // ==================================================================================
    @Transactional
    public Transaction withdraw(TransactionRequest request, String currentUsername) {
        // ✅ Idempotency Check with URI
        if (request.idempotencyKey() != null) {
            var existing = idempotencyService.getTransaction(
                request.idempotencyKey(),
                "/api/v1/transactions/withdraw"
            );
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        // ✅ FIX: Acquire the pessimistic lock FIRST, then validate ownership + PIN on
        // the locked row.  The previous code fetched the account twice — once without a
        // lock for validation (validateOwnerAndPin) and once with a lock (fetchWithLock)
        // — creating a TOCTOU window: an attacker could change the PIN between the two
        // reads, and the code would still allow the withdrawal.  Doing everything on the
        // locked row eliminates the race and removes a redundant DB round-trip.
        Account account = fetchWithLock(request.fromAccountNumber());

        if (!account.getUser().getUsername().equals(currentUsername)) {
            throw new RuntimeException("⛔ You do not own this account!");
        }
        String pin = request.pin() != null ? request.pin() : "0000";
        if (!passwordEncoder.matches(pin, account.getUser().getPin())) {
            throw new RuntimeException("❌ Invalid PIN");
        }

        try {
            if (account.getBalance().compareTo(request.amount()) < 0) {
                throw new RuntimeException("❌ Insufficient funds");
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

    // ==================================================================================
    // 💰 3. DEPOSIT (SECURE)
    // ==================================================================================
    @Transactional
    public Transaction deposit(TransactionRequest request) {
        // ✅ Idempotency Check with URI
        if (request.idempotencyKey() != null) {
            var existing = idempotencyService.getTransaction(
                request.idempotencyKey(),
                "/api/v1/transactions/deposit"
            );
            if (existing.isPresent()) {
                return existing.get();
            }
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
            idempotencyService.cacheTransaction(
                request.idempotencyKey(),
                "/api/v1/transactions/deposit",
                tx
            );
        }

        eventPublisherService.publishTransactionCompletedEvent(tx);
        return tx;
    }

    // ==================================================================================
    // 🛠️ HELPER METHODS
    // ==================================================================================
    private Account fetchWithLock(String accNum) {
        return accountRepository.findByAccountNumberWithLock(accNum)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accNum));
    }

    private void validateOwnerAndPin(String accNum, String username, String pin) {
        // Read-only fetch for validation (No Lock needed yet)
        Account acc = accountRepository.findByAccountNumber(accNum)
                .orElseThrow(() -> new RuntimeException("Account not found"));

        if (!acc.getUser().getUsername().equals(username)) {
            throw new RuntimeException("⛔ You do not own this account!");
        }
        if (!passwordEncoder.matches(pin, acc.getUser().getPin())) {
            throw new RuntimeException("❌ Invalid PIN");
        }
    }

    private BigDecimal calculateFee(Account account) {
        // ✅ JAVA 21 SWITCH EXPRESSION (Cleaner & Faster)
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
    
    // ✅ Get transaction history for user (Java 21: Sequenced Collections)
    public List<TransactionResponse> getTransactionHistory(String username) {
        List<Transaction> transactions = transactionRepository.findAllByUser(username);
        
        // ✅ JAVA 21: Use var for obvious types
        return transactions.stream()
                .map(this::toTransactionResponse)
                .toList(); // ✅ Java 16+: Simpler than collect(Collectors.toList())
    }
    
    // ✅ Get last transaction (Java 21: Sequenced Collections)
    public TransactionResponse getLastTransaction(String username) {
        List<Transaction> transactions = transactionRepository.findAllByUser(username);
        
        if (transactions.isEmpty()) {
            throw new RuntimeException("No transactions found");
        }
        
        // ✅ JAVA 21: Direct access to last element
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
                transaction.getNote(), // Use note instead of description
                transaction.getTimestamp(),
                currency,
                BigDecimal.ZERO, // TODO: Calculate fee
                transaction.getIdempotencyKey() // Use idempotencyKey instead of transactionReference
        );
    }
}