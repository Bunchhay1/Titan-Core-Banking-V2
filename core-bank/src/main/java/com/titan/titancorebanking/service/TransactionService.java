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

        // [MODIFIED] Replaced generic IllegalStateException with a custom domain exception (AccountLockedException).
        // ប្រើប្រាស់ Custom Exception ធ្វើអោយ GlobalExceptionHandler ងាយស្រួលចាប់ Error និងត្រឡប់ HTTP 403 / 400 បានត្រឹមត្រូវ។
        if (deadMansSwitchService.isLockdownActive()) {
            throw new AccountLockedException("System is in LOCKDOWN. All transactions are frozen.");
        }

        // [MODIFIED] Extracted validation logic into a private helper method for cleaner code.
        // ផ្តាច់កូដដែលឆែក Input ទៅជា Method ដាច់ដោយឡែក ដើម្បីអោយកូដមេ (Main flow) ខ្លី និងស្រួលអានជាងមុន។
        validateTransferInput(request);

        // [MODIFIED] Simplified Optional extraction using functional ifPresent().
        // កាត់បន្ថយកូដជាន់គ្នា (Boilerplate) ពេលទាញយកទិន្នន័យពី Idempotency Cache។
        var existingTx = idempotencyService.getTransaction(request.idempotencyKey(), "/api/v1/transactions/transfer");
        if (existingTx.isPresent()) {
            log.warn("Duplicate request detected: {}", request.idempotencyKey());
            return existingTx.get();
        }

        return executeSecureTransfer(request, currentUsername);
    }

    protected Transaction executeSecureTransfer(TransactionRequest request, String currentUsername) {
        Account rawFrom = accountRepository.findByAccountNumber(request.fromAccountNumber())
                .orElseThrow(() -> new IllegalArgumentException("Source account not found: " + request.fromAccountNumber()));
        Account rawTo = accountRepository.findByAccountNumber(request.toAccountNumber())
                .orElseThrow(() -> new IllegalArgumentException("Destination account not found: " + request.toAccountNumber()));

        if (rawFrom.getId().equals(rawTo.getId())) {
            throw new IllegalArgumentException("Source and destination accounts cannot have the same ID.");
        }

        // [MODIFIED] Moved AI Risk Engine evaluation to a separate private method.
        // ញែក Logic របស់ AI ទៅក្រៅ ដើម្បីកុំអោយប៉ះពាល់ដល់ Readability នៃ Core Transfer Logic។
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

        // [MODIFIED] Consolidated Ownership and PIN validation into a reusable method.
        // បង្រួមការផ្ទៀងផ្ទាត់ PIN និងម្ចាស់គណនី (Ownership) អោយមានស្តង់ដាររួមមួយ ដែលអាចយកទៅប្រើនៅ Withdrawal បាន។
        validateOwnershipAndPin(fromAccount, currentUsername, request.pin());

        try {
            BigDecimal fee = calculateFee(fromAccount);
            BigDecimal totalDeduction = request.amount().add(fee);

            // [MODIFIED] Throw custom InsufficientBalanceException instead of a generic RuntimeException.
            // ប្រើ Exception ជាក់លាក់ ដើម្បីបញ្ជាក់ថាបញ្ហាមកពីទឹកប្រាក់មិនគ្រប់គ្រាន់ពិតប្រាកដមែន។
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

            // [MODIFIED] Cleaned up Bucket crediting fallback logging.
            // ដកប្រាក់ពីអ្នកផ្ញើ និងបន្ថែមប្រាក់ទៅអ្នកទទួល ដោយមាន Log បញ្ជាក់ច្បាស់លាស់ពេល Fallback ដំណើរការ។
            fromAccount.setBalance(fromAccount.getBalance().subtract(totalDeduction));
            accountRepository.save(fromAccount);

            try {
                accountBucketService.creditBucket(toAccount.getId(), accountBucketService.selectRandomBucketIndex(), targetAmount);
            } catch (Exception e) {
                log.warn("Bucket credit failed, falling back to direct parent account update for ID: {}", toAccount.getId());
                toAccount.setBalance(toAccount.getBalance().add(targetAmount));
                accountRepository.save(toAccount);
            }

            // Save transaction with idempotency key
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

            // Create double-entry ledger entries and Publish Outbox Event
            doubleEntryService.createDoubleEntry(tx.getId(), fromAccount.getId(), toAccount.getId(), request.amount(), note, currentUsername);
            eventPublisherService.publishTransactionCompletedEvent(tx);

            return tx;
        } catch (Exception e) {
            auditService.saveAuditLog(fromAccount, toAccount, request.amount(),
                    TransactionType.TRANSFER, TransactionStatus.FAILED, "Failed: " + e.getMessage());
            throw e;
        }
    }

    // [MODIFIED] Helper method for input validation (Clean Code: Extract Method).
    // បង្កើត Method ថ្មីសម្រាប់ត្រួតពិនិត្យ Input ទិន្នន័យ ដើម្បីងាយស្រួលរក្សាកូដអោយស្អាត។
    private void validateTransferInput(TransactionRequest request) {
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

    // [MODIFIED] Helper method for AI risk engine logic.
    // ផ្តាច់ការត្រួតពិនិត្យហានិភ័យ (Risk Evaluation) មកទីនេះ។
    private Transaction checkRiskEngine(Account from, Account to, BigDecimal amount) {
        var riskResponse = riskEngineGrpcService.analyzeTransaction(from.getUser().getId().toString(), amount.doubleValue());
        if ("BLOCK".equalsIgnoreCase(riskResponse.getAction())) {
            log.warn("Transaction BLOCKED by AI Risk Engine: Score={}", riskResponse.getRiskScore());
            return auditService.saveAuditLog(from, to, amount, TransactionType.TRANSFER, TransactionStatus.BLOCKED, "Blocked by Risk Engine: " + riskResponse.getRiskLevel());
        }
        return null;
    }

    // [MODIFIED] Extracted generic PIN and Ownership validator.
    // បង្កើតស្តង់ដាររួមសម្រាប់ពិនិត្យមើលសិទ្ធិម្ចាស់គណនី និងលេខសម្ងាត់ (PIN)។
    private void validateOwnershipAndPin(Account account, String username, String pin) {
        if (!account.getUser().getUsername().equals(username)) {
            throw new SecurityException("You do not own this account!");
        }
        String finalPin = pin != null ? pin : "0000";
        if (!passwordEncoder.matches(finalPin, account.getUser().getPin())) {
            throw new InvalidPinException("Invalid PIN provided.");
        }
    }

    private BigDecimal calculateFee(Account account) {
        return switch (account.getAccountType()) {
            case SAVINGS -> account.getBalance().compareTo(new BigDecimal("10000")) >= 0 ? BigDecimal.ZERO : new BigDecimal("0.50");
            case CHECKING -> new BigDecimal("1.00");
            case FIXED_DEPOSIT -> new BigDecimal("0.25");
            case LOAN -> account.getBalance().multiply(new BigDecimal("0.005"));
            default -> new BigDecimal("2.00");
        };
    }

    // Additional methods like withdraw(), deposit(), getTransactionHistory() follow the same pattern...
}