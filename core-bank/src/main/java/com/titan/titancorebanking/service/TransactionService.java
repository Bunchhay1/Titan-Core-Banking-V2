package com.titan.titancorebanking.service;

import com.titan.titancorebanking.dto.request.TransactionRequest;
import com.titan.titancorebanking.dto.response.TransactionResponse;
import com.titan.titancorebanking.exception.AccountLockedException;
import com.titan.titancorebanking.exception.InsufficientBalanceException;
import com.titan.titancorebanking.exception.InvalidPinException;
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

    // ==================================================================================
    // 1. TRANSFER
    // ==================================================================================
    @Transactional
    @Retry(name = "db")
    public Transaction transfer(final TransactionRequest request, final String currentUsername) {

        if (deadMansSwitchService.isLockdownActive()) {
            throw new AccountLockedException("System is in LOCKDOWN. All transactions are frozen.");
        }

        validateTransferInput(request);

        var existingTx = idempotencyService.getTransaction(request.idempotencyKey(), "/api/v1/transactions/transfer");
        if (existingTx.isPresent()) {
            log.warn("Duplicate transfer request detected: {}", request.idempotencyKey());
            return existingTx.get();
        }

        return executeSecureTransfer(request, currentUsername);
    }

    protected Transaction executeSecureTransfer(final TransactionRequest request, final String currentUsername) {
        Account rawFrom = accountRepository.findByAccountNumber(request.fromAccountNumber())
                .orElseThrow(() -> new IllegalArgumentException("Source account not found: " + request.fromAccountNumber()));
        Account rawTo = accountRepository.findByAccountNumber(request.toAccountNumber())
                .orElseThrow(() -> new IllegalArgumentException("Destination account not found: " + request.toAccountNumber()));

        if (rawFrom.getId().equals(rawTo.getId())) {
            throw new IllegalArgumentException("Source and destination accounts cannot have the same ID.");
        }

        Transaction blockedTx = checkRiskEngine(rawFrom, rawTo, request.amount());
        if (blockedTx != null) return blockedTx;

        // DETERMINISTIC LOCK ORDERING (ID smaller first) - Prevents Deadlocks
        Long firstLockId = Math.min(rawFrom.getId(), rawTo.getId());
        Long secondLockId = Math.max(rawFrom.getId(), rawTo.getId());

        log.debug("Deterministic locking order: acquiring lock for ID {} then ID {}", firstLockId, secondLockId);

        Account firstLocked = accountRepository.findByIdWithLock(firstLockId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found for locking: ID " + firstLockId));
        Account secondLocked = accountRepository.findByIdWithLock(secondLockId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found for locking: ID " + secondLockId));

        Account fromAccount = firstLocked.getId().equals(rawFrom.getId()) ? firstLocked : secondLocked;
        Account toAccount = firstLocked.getId().equals(rawTo.getId()) ? firstLocked : secondLocked;

        validateOwnershipAndPin(fromAccount, currentUsername, request.pin());

        try {
            BigDecimal fee = calculateFee(fromAccount);
            BigDecimal totalDeduction = request.amount().add(fee);

            if (fromAccount.getBalance().compareTo(totalDeduction) < 0) {
                throw new InsufficientBalanceException("Insufficient Funds. Balance: " + fromAccount.getBalance());
            }

            // FX CALCULATION
            BigDecimal targetAmount = request.amount();
            String note = request.note() == null ? "" : request.note();
            if (fromAccount.getCurrency() != toAccount.getCurrency()) {
                targetAmount = exchangeRateService.convert(request.amount(), fromAccount.getCurrency(), toAccount.getCurrency());
                note += String.format(" [FX: %s -> %s]", fromAccount.getCurrency(), toAccount.getCurrency());
            }

            fromAccount.setBalance(fromAccount.getBalance().subtract(totalDeduction));
            accountRepository.save(fromAccount);

            // Deposit into high-concurrency bucket
            try {
                accountBucketService.creditBucket(toAccount.getId(), accountBucketService.selectRandomBucketIndex(), targetAmount);
            } catch (Exception e) {
                log.warn("Bucket credit failed, falling back to direct parent account update for ID: {}", toAccount.getId());
                toAccount.setBalance(toAccount.getBalance().add(targetAmount));
                accountRepository.save(toAccount);
            }

            Transaction tx = auditService.saveAuditLog(fromAccount, toAccount, request.amount(),
                    TransactionType.TRANSFER, TransactionStatus.SUCCESS, note);

            if (request.idempotencyKey() != null) {
                tx.setIdempotencyKey(request.idempotencyKey());
                transactionRepository.save(tx);
                idempotencyService.cacheTransaction(request.idempotencyKey(), "/api/v1/transactions/transfer", tx);
            }

            doubleEntryService.createDoubleEntry(tx.getId(), fromAccount.getId(), toAccount.getId(), request.amount(), note, currentUsername);
            eventPublisherService.publishTransactionCompletedEvent(tx);

            return tx;
        } catch (InsufficientBalanceException | InvalidPinException | AccountLockedException | SecurityException e) {
            // ✅ FIX: Re-throw business rule violations without audit logging
            // These should reach GlobalExceptionHandler with proper HTTP status codes
            log.warn("Business rule violation in transfer: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            // ✅ FIX: Only infrastructure/unexpected exceptions are logged as FAILED transactions
            log.error("Infrastructure failure in transfer: {}", e.getMessage(), e);
            auditService.saveAuditLog(fromAccount, toAccount, request.amount(),
                    TransactionType.TRANSFER, TransactionStatus.FAILED, "System failure: " + e.getMessage());
            throw e;
        }
    }

    // ==================================================================================
    // 2. WITHDRAWAL
    // ==================================================================================
    @Transactional
    @Retry(name = "db")
    public Transaction withdraw(final TransactionRequest request, final String currentUsername) {

        if (deadMansSwitchService.isLockdownActive()) {
            throw new AccountLockedException("System is in LOCKDOWN. All transactions are frozen.");
        }

        if (request.fromAccountNumber() == null || request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Valid source account number and positive amount are required.");
        }

        var existingTx = idempotencyService.getTransaction(request.idempotencyKey(), "/api/v1/transactions/withdraw");
        if (existingTx.isPresent()) {
            log.warn("Duplicate withdraw request detected: {}", request.idempotencyKey());
            return existingTx.get();
        }

        // [MODIFIED] Closed the TOCTOU race condition by verifying PIN strictly against the row-locked entity.
        // ចាប់យក Pessimistic Lock ជាមុន ទើបផ្ទៀងផ្ទាត់ PIN និងម្ចាស់គណនី ដើម្បីការពារមិនឲ្យមានអ្នកផ្លាស់ប្តូរលេខសម្ងាត់ចន្លោះពេលកំពុងដកប្រាក់។
        Account account = accountRepository.findByAccountNumberWithLock(request.fromAccountNumber())
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + request.fromAccountNumber()));

        validateOwnershipAndPin(account, currentUsername, request.pin());

        try {
            BigDecimal fee = calculateFee(account);
            BigDecimal totalDeduction = request.amount().add(fee);

            if (account.getBalance().compareTo(totalDeduction) < 0) {
                throw new InsufficientBalanceException("Insufficient Funds. Balance: " + account.getBalance());
            }

            account.setBalance(account.getBalance().subtract(totalDeduction));
            accountRepository.save(account);

            String note = "Withdrawal" + (request.note() != null ? " - " + request.note() : "");
            Transaction tx = auditService.saveAuditLog(account, null, request.amount(),
                    TransactionType.WITHDRAWAL, TransactionStatus.SUCCESS, note);

            if (request.idempotencyKey() != null) {
                tx.setIdempotencyKey(request.idempotencyKey());
                transactionRepository.save(tx);
                idempotencyService.cacheTransaction(request.idempotencyKey(), "/api/v1/transactions/withdraw", tx);
            }

            eventPublisherService.publishTransactionCompletedEvent(tx);
            return tx;
        } catch (InsufficientBalanceException | InvalidPinException | AccountLockedException | SecurityException e) {
            // ✅ FIX: Re-throw business rule violations without audit logging
            // These should reach GlobalExceptionHandler with proper HTTP status codes
            log.warn("Business rule violation in withdrawal: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            // ✅ FIX: Only infrastructure/unexpected exceptions are logged as FAILED transactions
            log.error("Infrastructure failure in withdrawal: {}", e.getMessage(), e);
            auditService.saveAuditLog(account, null, request.amount(),
                    TransactionType.WITHDRAWAL, TransactionStatus.FAILED, "System failure: " + e.getMessage());
            throw e;
        }
    }

    // ==================================================================================
    // 3. DEPOSIT
    // ==================================================================================
    @Transactional
    @Retry(name = "db")
    public Transaction deposit(final TransactionRequest request) {

        if (deadMansSwitchService.isLockdownActive()) {
            throw new AccountLockedException("System is in LOCKDOWN. All transactions are frozen.");
        }

        String targetAccNum = request.toAccountNumber() != null ? request.toAccountNumber() : request.fromAccountNumber();

        if (targetAccNum == null || request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Valid destination account number and positive amount are required.");
        }

        var existingTx = idempotencyService.getTransaction(request.idempotencyKey(), "/api/v1/transactions/deposit");
        if (existingTx.isPresent()) {
            log.warn("Duplicate deposit request detected: {}", request.idempotencyKey());
            return existingTx.get();
        }

        Account account = accountRepository.findByAccountNumberWithLock(targetAccNum)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + targetAccNum));

        try {
            // [MODIFIED] Redirected deposits through the AccountBucketService to absorb high concurrency.
            // រុញការដាក់ប្រាក់ចូលទៅកាន់ Bucket Partitioning ដើម្បីកុំឲ្យ Database គាំងនៅពេលមានមនុស្សដាក់ប្រាក់ចូលគណនីតែមួយ (Hot Account) រាប់ពាន់ដងក្នុង១វិនាទី។
            try {
                accountBucketService.creditBucket(account.getId(), accountBucketService.selectRandomBucketIndex(), request.amount());
            } catch (Exception e) {
                log.warn("Bucket credit failed, falling back to direct parent account update for ID: {}", account.getId());
                account.setBalance(account.getBalance().add(request.amount()));
                accountRepository.save(account);
            }

            String note = "Deposit" + (request.note() != null ? " - " + request.note() : "");
            Transaction tx = auditService.saveAuditLog(null, account, request.amount(),
                    TransactionType.DEPOSIT, TransactionStatus.SUCCESS, note);

            if (request.idempotencyKey() != null) {
                tx.setIdempotencyKey(request.idempotencyKey());
                transactionRepository.save(tx);
                idempotencyService.cacheTransaction(request.idempotencyKey(), "/api/v1/transactions/deposit", tx);
            }

            eventPublisherService.publishTransactionCompletedEvent(tx);
            return tx;
        } catch (AccountLockedException | SecurityException e) {
            // ✅ FIX: Re-throw business rule violations without audit logging
            // These should reach GlobalExceptionHandler with proper HTTP status codes
            log.warn("Business rule violation in deposit: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            // ✅ FIX: Only infrastructure/unexpected exceptions are logged as FAILED transactions
            log.error("Infrastructure failure in deposit: {}", e.getMessage(), e);
            auditService.saveAuditLog(null, account, request.amount(),
                    TransactionType.DEPOSIT, TransactionStatus.FAILED, "System failure: " + e.getMessage());
            throw e;
        }
    }

    // ==================================================================================
    // HISTORY & READ METHODS
    // ==================================================================================
    @Transactional(readOnly = true)
    public List<TransactionResponse> getTransactionHistory(final String username) {
        List<Transaction> transactions = transactionRepository.findAllByUser(username);
        return transactions.stream().map(this::toTransactionResponse).toList();
    }

    @Transactional(readOnly = true)
    public TransactionResponse getLastTransaction(final String username) {
        List<Transaction> transactions = transactionRepository.findAllByUser(username);
        if (transactions.isEmpty()) {
            throw new IllegalArgumentException("No transactions found for user.");
        }
        // Utilizing Java 21 Sequenced Collections interface directly
        return toTransactionResponse(transactions.getLast());
    }

    // ==================================================================================
    // PRIVATE HELPER METHODS
    // ==================================================================================

    private void validateTransferInput(final TransactionRequest request) {
        if (request.fromAccountNumber() == null || request.toAccountNumber() == null) {
            throw new IllegalArgumentException("Source and destination account numbers are required.");
        }
        if (request.fromAccountNumber().trim().equalsIgnoreCase(request.toAccountNumber().trim())) {
            throw new IllegalArgumentException("Cannot transfer funds to the same account.");
        }
        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Transfer amount must be strictly positive.");
        }
    }

    private Transaction checkRiskEngine(final Account from, final Account to, final BigDecimal amount) {
        var riskResponse = riskEngineGrpcService.analyzeTransaction(from.getUser().getId().toString(), amount.doubleValue());
        if ("BLOCK".equalsIgnoreCase(riskResponse.getAction())) {
            log.warn("Transaction BLOCKED by AI Risk Engine: Score={}", riskResponse.getRiskScore());
            return auditService.saveAuditLog(from, to, amount, TransactionType.TRANSFER, TransactionStatus.BLOCKED, "Blocked by Risk Engine: " + riskResponse.getRiskLevel());
        }
        return null;
    }

    private void validateOwnershipAndPin(final Account account, final String username, final String pin) {
        if (!account.getUser().getUsername().equals(username)) {
            throw new SecurityException("You do not own this account!");
        }
        String finalPin = pin != null ? pin : "0000";
        if (!passwordEncoder.matches(finalPin, account.getUser().getPin())) {
            throw new InvalidPinException("Invalid PIN provided.");
        }
    }

    private BigDecimal calculateFee(final Account account) {
        return switch (account.getAccountType()) {
            case SAVINGS -> account.getBalance().compareTo(new BigDecimal("10000")) >= 0 ? BigDecimal.ZERO : new BigDecimal("0.50");
            case CHECKING -> new BigDecimal("1.00");
            case FIXED_DEPOSIT -> new BigDecimal("0.25");
            case LOAN -> account.getBalance().multiply(new BigDecimal("0.005"));
            default -> new BigDecimal("2.00");
        };
    }

    private TransactionResponse toTransactionResponse(final Transaction tx) {
        var currency = Optional.ofNullable(tx.getFromAccount())
                .map(Account::getCurrency)
                .map(Enum::name)
                .orElseGet(() -> Optional.ofNullable(tx.getToAccount())
                        .map(Account::getCurrency)
                        .map(Enum::name)
                        .orElse("USD"));

        return new TransactionResponse(
                tx.getId(),
                tx.getTransactionType().name(),
                tx.getAmount(),
                tx.getFromAccount() != null ? tx.getFromAccount().getAccountNumber() : null,
                tx.getToAccount() != null ? tx.getToAccount().getAccountNumber() : null,
                tx.getStatus().name(),
                tx.getNote(),
                tx.getTimestamp(),
                currency,
                BigDecimal.ZERO,
                tx.getIdempotencyKey() != null ? tx.getIdempotencyKey() : tx.getTransactionReference()
        );
    }
}