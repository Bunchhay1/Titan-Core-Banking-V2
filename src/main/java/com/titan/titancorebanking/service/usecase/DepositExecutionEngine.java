package com.titan.titancorebanking.service.usecase;

import com.titan.titancorebanking.dto.request.TransactionRequest;
import com.titan.titancorebanking.enums.TransactionStatus;
import com.titan.titancorebanking.enums.TransactionType;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class DepositExecutionEngine {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionAuditService auditService;
    private final DoubleEntryService doubleEntryService;
    private final EventPublisherService eventPublisherService;
    private final IdempotencyService idempotencyService;
    private final EntityManager entityManager;
    private final TransactionDomainValidator validator;
    private final com.titan.titancorebanking.security.SecurityAuthorizationHelper securityHelper;

    @Retry(name = "db")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transaction executeSecureDepositUnderLock(
            TransactionRequest request,
            String targetAccNum,
            String callerIdentity,
            boolean isTrustedInternal) {

        Account account = accountRepository.findByAccountNumberWithLock(targetAccNum)
                .orElseThrow(() -> new EntityNotFoundException("Account not found: " + targetAccNum));

        entityManager.refresh(account);
        MDC.put("accountId", String.valueOf(account.getId()));
        log.info("Deposit initiated. amount={} currency={}", request.amount(), account.getCurrency());

        // ── In-lock idempotency double check ──
        if (request.idempotencyKey() != null) {
            Optional<Transaction> inLockExisting = transactionRepository.findByIdempotencyKey(request.idempotencyKey());
            if (inLockExisting.isPresent()) {
                Transaction cached = inLockExisting.get();
                validateDepositReplay(request, targetAccNum, cached, callerIdentity, isTrustedInternal);
                log.warn("Duplicate deposit detected under lock — returning cached result. key={}", request.idempotencyKey());
                return cached;
            }
        }

        validator.requireActiveAccount(account);

        if (!isTrustedInternal) {
            securityHelper.verifyDepositTargetAccess(account, callerIdentity);
        }

        // ── Execute credit ────────────────────────────────────────────────
        account.setBalance(account.getBalance().add(request.amount()));
        accountRepository.save(account);

        Transaction tx = auditService.saveSuccessAuditLog(
                null, account, request.amount(), BigDecimal.ZERO,
                TransactionType.DEPOSIT, TransactionStatus.SUCCESS, "Deposit");

        MDC.put("transactionId", String.valueOf(tx.getId()));

        if (request.idempotencyKey() != null) {
            tx.setIdempotencyKey(request.idempotencyKey());
            transactionRepository.save(tx);
            idempotencyService.cacheTransaction(request.idempotencyKey(), "/api/v1/transactions/deposit", tx);
        }

        // Balanced double-entry general ledger
        doubleEntryService.createDepositDoubleEntry(
                tx.getId(), account.getId(), request.amount(), "Deposit", callerIdentity);

        eventPublisherService.publishTransactionCompletedEvent(tx);
        log.info("Deposit completed. txId={} amount={}", tx.getId(), tx.getAmount());
        return tx;
    }

    public void validateDepositReplay(
            TransactionRequest request,
            String targetAccNum,
            Transaction cached,
            String callerIdentity,
            boolean isTrustedInternal) {

        securityHelper.verifyDepositReplayOwnership(cached.getToAccount(), callerIdentity, isTrustedInternal);

        if (request.amount().compareTo(cached.getAmount()) != 0) {
            throw new IllegalStateException(String.format(
                    "Idempotency conflict: key %s previously processed with amount %s, but requested amount is %s",
                    request.idempotencyKey(), cached.getAmount(), request.amount()));
        }

        if (cached.getToAccount() != null && !cached.getToAccount().getAccountNumber().equals(targetAccNum)) {
            throw new IllegalStateException(String.format(
                    "Idempotency conflict: key %s previously processed with account %s, but requested %s",
                    request.idempotencyKey(), cached.getToAccount().getAccountNumber(), targetAccNum));
        }
    }
}
