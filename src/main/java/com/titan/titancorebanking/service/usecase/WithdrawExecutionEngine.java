package com.titan.titancorebanking.service.usecase;

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
import com.titan.titancorebanking.service.validation.TransactionDomainValidator;
import io.github.resilience4j.retry.annotation.Retry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class WithdrawExecutionEngine {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionAuditService auditService;
    private final DoubleEntryService doubleEntryService;
    private final AccountBucketService accountBucketService;
    private final EventPublisherService eventPublisherService;
    private final IdempotencyService idempotencyService;
    private final EntityManager entityManager;
    private final TransactionDomainValidator validator;
    private final com.titan.titancorebanking.security.SecurityAuthorizationHelper securityHelper;

    @Retry(name = "db")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transaction executeSecureWithdrawUnderLock(
            TransactionRequest request,
            String currentUsername,
            LocalDateTime startOfDayUtc,
            BigDecimal dailyWithdrawLimit) {

        Account account = accountRepository.findByAccountNumberWithLock(request.fromAccountNumber())
                .orElseThrow(() -> new EntityNotFoundException("Account not found: " + request.fromAccountNumber()));

        entityManager.refresh(account);
        MDC.put("accountId", String.valueOf(account.getId()));
        log.info("Withdraw initiated. amount={} currency={}", request.amount(), account.getCurrency());

        // ── In-lock idempotency double check ──
        if (request.idempotencyKey() != null) {
            Optional<Transaction> inLockExisting = transactionRepository.findByIdempotencyKey(request.idempotencyKey());
            if (inLockExisting.isPresent()) {
                Transaction cached = inLockExisting.get();
                validateWithdrawReplay(request, cached, currentUsername);
                log.warn("Duplicate withdraw detected under lock — returning cached result. key={}", request.idempotencyKey());
                return cached;
            }
        }

        validator.requireActiveAccount(account);

        securityHelper.verifyAccountOwnership(account, currentUsername, "withdrawal");

        validator.validatePin(request.pin(), account);

        // ── In-lock daily withdrawal limit re-validation (closes TOCTOU race) ──
        BigDecimal confirmedWithdrawnToday = transactionRepository
                .sumOutboundWithdrawalsSince(account.getId(), startOfDayUtc);
        if (confirmedWithdrawnToday.add(request.amount()).compareTo(dailyWithdrawLimit) > 0) {
            throw new DailyLimitExceededException(String.format(
                    "Daily withdrawal limit of %s exceeded. Withdrawn today: %s, Requested: %s",
                    dailyWithdrawLimit, confirmedWithdrawnToday, request.amount()));
        }

        // ── Asymmetric Balance Protection: Aggregated balance check (main + buckets + overdraft) ──
        BigDecimal totalAggregatedBalance = accountBucketService != null
                ? accountBucketService.calculateTotalAggregatedBalance(account.getId(), account.getBalance())
                : null;
        if (totalAggregatedBalance == null) {
            totalAggregatedBalance = account.getBalance() != null ? account.getBalance() : BigDecimal.ZERO;
        }
        BigDecimal effectiveLimit = totalAggregatedBalance.add(validator.validateAndGetOverdraftLimit(account));
        if (effectiveLimit.compareTo(request.amount()) < 0) {
            throw new InsufficientBalanceException("Insufficient balance for withdrawal. Available: " + effectiveLimit);
        }

        // Consolidate bucket balances if main balance alone is insufficient
        if (account.getBalance().compareTo(request.amount()) < 0) {
            accountBucketService.sweepAccountBucketsUnderLock(account);
        }

        // ── Execute debit ─────────────────────────────────────────────────
        account.setBalance(account.getBalance().subtract(request.amount()));
        accountRepository.save(account);

        Transaction tx = auditService.saveSuccessAuditLog(
                account, null, request.amount(), BigDecimal.ZERO,
                TransactionType.WITHDRAWAL, TransactionStatus.SUCCESS, "Withdrawal");

        MDC.put("transactionId", String.valueOf(tx.getId()));

        if (request.idempotencyKey() != null) {
            tx.setIdempotencyKey(request.idempotencyKey());
            transactionRepository.save(tx);
            idempotencyService.cacheTransaction(request.idempotencyKey(), "/api/v1/transactions/withdraw", tx);
        }

        // Balanced double-entry general ledger
        doubleEntryService.createWithdrawDoubleEntry(
                tx.getId(), account.getId(), request.amount(), "Withdrawal", currentUsername);

        eventPublisherService.publishTransactionCompletedEvent(tx);
        log.info("Withdraw completed. txId={} amount={}", tx.getId(), tx.getAmount());
        return tx;
    }

    public void validateWithdrawReplay(TransactionRequest request, Transaction cached, String currentUsername) {
        securityHelper.verifyWithdrawReplayOwnership(cached.getFromAccount(), currentUsername);

        if (request.amount().compareTo(cached.getAmount()) != 0) {
            throw new IllegalStateException(String.format(
                    "Idempotency conflict: key %s previously processed with amount %s, but requested amount is %s",
                    request.idempotencyKey(), cached.getAmount(), request.amount()));
        }

        if (cached.getFromAccount() != null && !cached.getFromAccount().getAccountNumber().equals(request.fromAccountNumber())) {
            throw new IllegalStateException(String.format(
                    "Idempotency conflict: key %s previously processed with account %s, but requested %s",
                    request.idempotencyKey(), cached.getFromAccount().getAccountNumber(), request.fromAccountNumber()));
        }
    }
}
