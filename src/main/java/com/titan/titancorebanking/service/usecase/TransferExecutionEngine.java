package com.titan.titancorebanking.service.usecase;

import com.titan.titancorebanking.dto.request.TransactionRequest;
import com.titan.titancorebanking.enums.TransactionStatus;
import com.titan.titancorebanking.enums.TransactionType;
import com.titan.titancorebanking.exception.DailyLimitExceededException;
import com.titan.titancorebanking.exception.InsufficientBalanceException;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.MonetaryTransferBreakdown;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.TransactionRepository;
import com.titan.titancorebanking.service.*;
import com.titan.titancorebanking.service.imple.ExchangeRateService;
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
public class TransferExecutionEngine {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionAuditService auditService;
    private final DoubleEntryService doubleEntryService;
    private final AccountBucketService accountBucketService;
    private final ExchangeRateService exchangeRateService;
    private final EventPublisherService eventPublisherService;
    private final IdempotencyService idempotencyService;
    private final EntityManager entityManager;
    private final TransactionDomainValidator validator;
    private final com.titan.titancorebanking.security.SecurityAuthorizationHelper securityHelper;

    @Retry(name = "db")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transaction executeSecureTransferUnderLock(
            TransactionRequest request,
            String currentUsername,
            Long rawFromId,
            Long rawToId,
            BigDecimal fee,
            BigDecimal totalDeduction,
            LocalDateTime startOfDayUtc,
            BigDecimal dailyTransferLimit,
            boolean isSystemCaller) {

        if (rawFromId.equals(rawToId)) {
            throw new IllegalArgumentException("Cannot transfer to the same account: ID " + rawFromId);
        }

        // ── Acquire pessimistic locks in deterministic order ──────────────
        Long firstLockId = Math.min(rawFromId, rawToId);
        Long secondLockId = Math.max(rawFromId, rawToId);

        Account firstLocked = accountRepository.findByIdWithLock(firstLockId)
                .orElseThrow(() -> new EntityNotFoundException("Account not found for locking: ID " + firstLockId));
        Account secondLocked = accountRepository.findByIdWithLock(secondLockId)
                .orElseThrow(() -> new EntityNotFoundException("Account not found for locking: ID " + secondLockId));

        // Refresh Hibernate L1 cache to guarantee latest state after lock acquisition
        entityManager.refresh(firstLocked);
        entityManager.refresh(secondLocked);

        Account fromAccount = firstLocked.getId().equals(rawFromId) ? firstLocked : secondLocked;
        Account toAccount = firstLocked.getId().equals(rawToId) ? firstLocked : secondLocked;

        // ── In-lock idempotency double check (handles concurrent identical requests) ──
        if (request.idempotencyKey() != null) {
            Optional<Transaction> inLockExisting = transactionRepository.findByIdempotencyKey(request.idempotencyKey());
            if (inLockExisting.isPresent()) {
                Transaction cached = inLockExisting.get();
                validateTransferReplay(request, cached, currentUsername, isSystemCaller);
                log.warn("Duplicate transfer detected under lock — returning cached result. key={}", request.idempotencyKey());
                return cached;
            }
        }

        validator.requireActiveAccount(fromAccount);
        validator.requireActiveAccount(toAccount);

        securityHelper.verifyAccountOwnership(fromAccount, currentUsername, isSystemCaller ? "scheduled transfer" : "transfer");

        if (!isSystemCaller && request.pin() != null) {
            validator.validatePin(request.pin(), fromAccount);
        }

        // ── In-lock daily transfer limit re-validation (closes TOCTOU race) ──
        BigDecimal confirmedSentToday = transactionRepository
                .sumOutboundTransfersSince(fromAccount.getId(), startOfDayUtc);
        if (confirmedSentToday.add(totalDeduction).compareTo(dailyTransferLimit) > 0) {
            throw new DailyLimitExceededException(String.format(
                    "Daily transfer limit of %s exceeded. Sent today: %s, Requested (amount+fee): %s",
                    dailyTransferLimit, confirmedSentToday, totalDeduction));
        }

        // ── Asymmetric Balance Protection: Aggregated balance check (main + buckets + overdraft) ──
        BigDecimal totalAggregatedBalance = accountBucketService != null
                ? accountBucketService.calculateTotalAggregatedBalance(fromAccount.getId(), fromAccount.getBalance())
                : null;
        if (totalAggregatedBalance == null) {
            totalAggregatedBalance = fromAccount.getBalance() != null ? fromAccount.getBalance() : BigDecimal.ZERO;
        }
        BigDecimal effectiveLimit = totalAggregatedBalance.add(validator.validateAndGetOverdraftLimit(fromAccount));
        if (effectiveLimit.compareTo(totalDeduction) < 0) {
            throw new InsufficientBalanceException(String.format(
                    "Insufficient balance. Required: %s (amount %s + fee %s). Available: %s",
                    totalDeduction, request.amount(), fee, effectiveLimit));
        }

        // Consolidate bucket balances if main balance alone is insufficient to cover total deduction
        if (fromAccount.getBalance().compareTo(totalDeduction) < 0) {
            accountBucketService.sweepAccountBucketsUnderLock(fromAccount);
        }

        // FX conversion & canonical breakdown
        BigDecimal targetAmount = request.amount();
        String note = request.note() != null ? request.note() : "";
        BigDecimal exchangeRate = BigDecimal.ONE;
        String exchangeRef = "NONE";

        if (fromAccount.getCurrency() != toAccount.getCurrency()) {
            targetAmount = exchangeRateService.convert(
                    request.amount(), fromAccount.getCurrency(), toAccount.getCurrency());
            exchangeRate = targetAmount.divide(request.amount(), 6, java.math.RoundingMode.HALF_EVEN);
            exchangeRef = fromAccount.getCurrency() + "/" + toAccount.getCurrency();
            note += " [FX: " + exchangeRef + " @" + exchangeRate + "]";
        }

        MonetaryTransferBreakdown breakdown = new MonetaryTransferBreakdown(
                request.amount(),
                fromAccount.getCurrency(),
                fee,
                fromAccount.getCurrency(),
                totalDeduction,
                targetAmount,
                toAccount.getCurrency(),
                exchangeRate,
                exchangeRef
        );

        // ── Debit sender (amount + fee) ───────────────────────────────────
        fromAccount.setBalance(fromAccount.getBalance().subtract(breakdown.totalDeduction()));
        accountRepository.save(fromAccount);

        // ── Credit receiver via bucket partitioning ───────────────────────
        int bucketIndex = accountBucketService.selectRandomBucketIndex();
        accountBucketService.creditBucket(toAccount.getId(), bucketIndex, breakdown.targetAmount());

        // ── Success Audit Log (joins outer transaction) ───────────────────
        Transaction tx = auditService.saveSuccessAuditLog(
                fromAccount, toAccount, breakdown.sourceAmount(), breakdown.feeAmount(),
                TransactionType.TRANSFER, TransactionStatus.SUCCESS, note);

        MDC.put("transactionId", String.valueOf(tx.getId()));

        if (request.idempotencyKey() != null) {
            tx.setIdempotencyKey(request.idempotencyKey());
            transactionRepository.save(tx);
            idempotencyService.cacheTransaction(request.idempotencyKey(), "/api/v1/transactions/transfer", tx);
        }

        // ── Balanced Double-Entry General Ledger Posting ──────────────────
        if (fromAccount.getCurrency() == toAccount.getCurrency()) {
            doubleEntryService.createTransferDoubleEntry(
                    tx.getId(), fromAccount.getId(), toAccount.getId(),
                    breakdown.sourceAmount(), breakdown.feeAmount(), note, currentUsername);
        } else {
            doubleEntryService.createTransferDoubleEntry(
                    tx.getId(), fromAccount.getId(), toAccount.getId(),
                    breakdown.sourceAmount(), breakdown.targetAmount(), breakdown.feeAmount(),
                    note, currentUsername);
        }

        // ── Event publication ─────────────────────────────────────────────
        eventPublisherService.publishTransactionCompletedEvent(tx);
        log.info("Transfer posted. txId={} from={} to={} amount={} fee={} currency={}",
                tx.getId(), fromAccount.getAccountNumber(), toAccount.getAccountNumber(),
                breakdown.sourceAmount(), breakdown.feeAmount(), fromAccount.getCurrency());

        return tx;
    }

    public void validateTransferReplay(TransactionRequest request, Transaction cached, String currentUsername, boolean isSystemCaller) {
        securityHelper.verifyTransferReplayOwnership(cached.getFromAccount(), currentUsername, isSystemCaller);

        if (request.amount().compareTo(cached.getAmount()) != 0) {
            throw new IllegalStateException(String.format(
                    "Idempotency conflict: key %s previously processed with amount %s, but requested amount is %s",
                    request.idempotencyKey(), cached.getAmount(), request.amount()));
        }

        if (cached.getFromAccount() != null && !cached.getFromAccount().getAccountNumber().equals(request.fromAccountNumber())) {
            throw new IllegalStateException(String.format(
                    "Idempotency conflict: key %s previously processed with source account %s, but requested %s",
                    request.idempotencyKey(), cached.getFromAccount().getAccountNumber(), request.fromAccountNumber()));
        }

        if (cached.getToAccount() != null && !cached.getToAccount().getAccountNumber().equals(request.toAccountNumber())) {
            throw new IllegalStateException(String.format(
                    "Idempotency conflict: key %s previously processed with destination account %s, but requested %s",
                    request.idempotencyKey(), cached.getToAccount().getAccountNumber(), request.toAccountNumber()));
        }
    }
}
