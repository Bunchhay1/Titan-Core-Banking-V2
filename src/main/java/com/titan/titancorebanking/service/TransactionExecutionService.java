package com.titan.titancorebanking.service;

import com.titan.titancorebanking.dto.request.TransactionRequest;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.model.OutboxEvent;
import com.titan.titancorebanking.enums.TransactionStatus;
import com.titan.titancorebanking.enums.TransactionType;
import com.titan.titancorebanking.exception.InsufficientFundsException;
import com.titan.titancorebanking.exception.LockAcquisitionException;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.TransactionRepository;
import com.titan.titancorebanking.repository.OutboxRepository;
import com.titan.titancorebanking.service.strategy.FeeCalculationStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Handles strict ACID state transitions.
 * ZERO Network I/O inside the transaction boundaries.
 * Enforces Transactional Outbox for eventual consistency.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionExecutionService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final OutboxRepository outboxRepository; // Replaces direct EventPublisher
    private final TransactionAuditService auditService;
    private final DoubleEntryService doubleEntryService;
    private final FeeCalculationStrategy feeCalculationStrategy; // OCP compliance

    // REQUIRES_NEW ensures a fresh Hibernate Session if retried by Resilience4j
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    @Retry(name = "db_concurrency")
    public Transaction executeTransferAtomically(
            TransactionRequest request,
            Long rawFromId,
            Long rawToId,
            BigDecimal targetAmount, // Passed in: FX calculated OUTSIDE this transaction
            String currentUsername) {

        // 1. Deterministic Lock Acquisition (Min-ID to Max-ID)
        Long firstLockId = Math.min(rawFromId, rawToId);
        Long secondLockId = Math.max(rawFromId, rawToId);

        Account firstLocked = accountRepository.findByIdWithLock(firstLockId)
                .orElseThrow(() -> new LockAcquisitionException("Lock acquisition failed for ID " + firstLockId));
        Account secondLocked = accountRepository.findByIdWithLock(secondLockId)
                .orElseThrow(() -> new LockAcquisitionException("Lock acquisition failed for ID " + secondLockId));

        Account fromAccount = firstLocked.getId().equals(rawFromId) ? firstLocked : secondLocked;
        Account toAccount = firstLocked.getId().equals(rawToId) ? firstLocked : secondLocked;

        // 2. Encapsulated Business Logic (OCP Compliant)
        BigDecimal fee = feeCalculationStrategy.calculateFee(fromAccount);
        BigDecimal totalDeduction = request.amount().add(fee);

        if (fromAccount.getBalance().compareTo(totalDeduction) < 0) {
            throw new InsufficientFundsException("Insufficient Funds for transfer and fees.");
        }

        // 3. State Mutation
        fromAccount.setBalance(fromAccount.getBalance().subtract(totalDeduction));
        toAccount.setBalance(toAccount.getBalance().add(targetAmount));

        accountRepository.save(fromAccount);
        accountRepository.save(toAccount);

        // 4. Audit & Double Entry
        Transaction tx = auditService.saveAuditLog(fromAccount, toAccount, request.amount(),
                TransactionType.TRANSFER, TransactionStatus.SUCCESS, request.note());

        if (request.idempotencyKey() != null) {
            tx.setIdempotencyKey(request.idempotencyKey());
            transactionRepository.save(tx);
        }

        doubleEntryService.createDoubleEntry(tx.getId(), fromAccount.getId(), toAccount.getId(), request.amount(), request.note(), currentUsername);

        // 5. Transactional Outbox (Step 86: Eventual Consistency Management)
        // Persist the event in the same ACID transaction. A separate relay process will publish to Kafka/Redis.
        outboxRepository.save(OutboxEvent.fromTransaction(tx));

        return tx;
    }
}