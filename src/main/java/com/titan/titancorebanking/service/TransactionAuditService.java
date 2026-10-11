package com.titan.titancorebanking.service;

import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.enums.TransactionStatus;
import com.titan.titancorebanking.enums.TransactionType;
import com.titan.titancorebanking.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionAuditService {

    private final TransactionRepository transactionRepository;

    /**
     * Persists a SUCCESS or BLOCKED audit record inside the <em>caller's</em> transaction.
     *
     * <p>Uses {@code Propagation.MANDATORY} — this method must be invoked from within
     * an active {@code @Transactional} boundary. The audit record is committed atomically
     * with the surrounding money-movement (debit, credit, ledger, outbox). If anything
     * after this call causes the outer transaction to roll back, this record rolls back
     * too — preventing phantom SUCCESS entries for transfers that never actually settled.
     *
     * <p>Use this method for all SUCCESS and BLOCKED outcomes on the main transfer path.
     * Use {@link #saveAuditLog} (REQUIRES_NEW) only for FAILED outcomes where you
     * explicitly want a durable record even if the outer transaction rolls back.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Transaction saveSuccessAuditLog(Account from, Account to, BigDecimal amount, BigDecimal fee,
                                           TransactionType type, TransactionStatus status, String note) {
        return buildAndSave(from, to, amount, fee, type, status, note);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Transaction saveSuccessAuditLog(Account from, Account to, BigDecimal amount,
                                           TransactionType type, TransactionStatus status, String note) {
        return buildAndSave(from, to, amount, BigDecimal.ZERO, type, status, note);
    }

    /**
     * Persists a FAILED or durable audit record in an <em>independent</em> transaction.
     *
     * <p>Runs in {@code REQUIRES_NEW} — suspends the caller's transaction and opens a
     * new one. This record is committed regardless of whether the outer transaction rolls
     * back, providing a durable audit trail for every failed or blocked operation.
     *
     * <p>Use this method for FAILED outcomes in catch blocks, and for BLOCKED outcomes
     * on the risk-engine fast path (where there is no outer money-movement transaction).
     *
     * <p>Transaction reference uses UUID to guarantee global uniqueness under concurrent
     * high-TPS load.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transaction saveAuditLog(Account from, Account to, BigDecimal amount, BigDecimal fee,
                                    TransactionType type, TransactionStatus status, String note) {
        return buildAndSave(from, to, amount, fee, type, status, note);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transaction saveAuditLog(Account from, Account to, BigDecimal amount,
                                    TransactionType type, TransactionStatus status, String note) {
        return buildAndSave(from, to, amount, BigDecimal.ZERO, type, status, note);
    }

    // ── Shared builder — called by both propagation variants ─────────────────

    private Transaction buildAndSave(Account from, Account to, BigDecimal amount, BigDecimal fee,
                                     TransactionType type, TransactionStatus status, String note) {
        // UUID guarantees uniqueness across all nodes without coordination.
        // "TX-" prefix for readability in support/ops tooling.
        String txRef = "TX-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24).toUpperCase();

        Transaction tx = Transaction.builder()
                .transactionType(type)
                .fromAccount(from)   // null for Deposit
                .toAccount(to)       // null for Withdrawal
                .amount(amount)
                .fee(fee != null ? fee : BigDecimal.ZERO)
                .status(status)
                .note(note)
                .timestamp(LocalDateTime.now(java.time.ZoneOffset.UTC))  // always UTC
                .transactionReference(txRef)
                .build();

        return transactionRepository.save(tx);
    }
}
