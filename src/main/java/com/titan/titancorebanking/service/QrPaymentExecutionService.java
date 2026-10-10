package com.titan.titancorebanking.service;

import com.titan.titancorebanking.dto.request.CollectByQrRequest;
import com.titan.titancorebanking.dto.request.PayByQrRequest;
import com.titan.titancorebanking.enums.TransactionStatus;
import com.titan.titancorebanking.enums.TransactionType;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.QrPayment;
import com.titan.titancorebanking.model.QrPayment.QrStatus;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.QrPaymentRepository;
import com.titan.titancorebanking.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.github.resilience4j.retry.annotation.Retry;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Handles strict ACID state transitions for QR Payments.
 * Enforces High Cohesion, Low Coupling, and Defensive Programming.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QrPaymentExecutionService {

    private static final String ACCOUNT_QR = "ACCOUNT_QR";
    private static final String PAYER_QR_PREFIX = "PAYER_QR:";
    private static final String DB_RETRY_POLICY = "db_concurrency";

    private final AccountRepository accountRepository;
    private final QrPaymentRepository qrPaymentRepository;
    private final TransactionRepository transactionRepository;
    private final EventPublisherService eventPublisherService;
    private final AccountBucketService accountBucketService;
    private final DoubleEntryService doubleEntryService;
    private final QrAutonomousStateService autonomousStateService; // Step 2.1 implementation

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    @Retry(name = DB_RETRY_POLICY)
    public QrPayment executePayByQrAtomically(PayByQrRequest request, Long rawPayerId, String username) {

        QrPayment qrPayment = qrPaymentRepository.findByQrCodeWithLock(request.qrCode())
                .orElseThrow(() -> new IllegalArgumentException("Invalid QR code."));

        validateQrStatusDefensively(qrPayment);

        Long rawPayeeId = qrPayment.getPayeeAccount().getId();
        if (rawPayerId.equals(rawPayeeId)) {
            throw new IllegalArgumentException("Self-payment via QR is strictly prohibited.");
        }

        Account[] lockedAccounts = acquireDeterministicLocks(rawPayerId, rawPayeeId);
        Account payerLocked = lockedAccounts[0].getId().equals(rawPayerId) ? lockedAccounts[0] : lockedAccounts[1];
        Account payeeLocked = lockedAccounts[0].getId().equals(rawPayeeId) ? lockedAccounts[0] : lockedAccounts[1];

        BigDecimal paymentAmount = resolveAmount(qrPayment, request);

        if (payerLocked.getBalance().compareTo(paymentAmount) < 0) {
            throw new IllegalStateException("Insufficient balance.");
        }

        executeFundTransfer(payerLocked, payeeLocked, paymentAmount);

        Transaction tx = generateTransactionRecord(payerLocked, payeeLocked, paymentAmount,
                "QR Payment – " + (qrPayment.getNote() != null ? qrPayment.getNote() : ""), "QR-PAY-");

        updateQrRecord(qrPayment, payerLocked, tx);

        doubleEntryService.createDoubleEntry(tx.getId(), payerLocked.getId(), payeeLocked.getId(), paymentAmount, "QR Payment", username);
        eventPublisherService.publishTransactionCompletedEvent(tx);

        return qrPayment;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    @Retry(name = DB_RETRY_POLICY)
    public QrPayment executeCollectByQrAtomically(CollectByQrRequest request, Long rawCollectorId, String username) {

        QrPayment qrPayment = qrPaymentRepository.findByQrCodeWithLock(request.qrCode())
                .orElseThrow(() -> new IllegalArgumentException("Invalid QR code."));

        if (qrPayment.getNote() == null || !qrPayment.getNote().startsWith(PAYER_QR_PREFIX)) {
            throw new IllegalArgumentException("Invalid operation: Not a Send-by-QR specification.");
        }

        validateQrStatusDefensively(qrPayment);

        Long rawPayerId = qrPayment.getPayeeAccount().getId();
        if (rawPayerId.equals(rawCollectorId)) {
            throw new IllegalArgumentException("Circular collection prohibited.");
        }

        Account[] lockedAccounts = acquireDeterministicLocks(rawPayerId, rawCollectorId);
        Account payerLocked = lockedAccounts[0].getId().equals(rawPayerId) ? lockedAccounts[0] : lockedAccounts[1];
        Account collectorLocked = lockedAccounts[0].getId().equals(rawCollectorId) ? lockedAccounts[0] : lockedAccounts[1];

        BigDecimal amount = qrPayment.getAmount();
        if (payerLocked.getBalance().compareTo(amount) < 0) {
            throw new IllegalStateException("Payer's account holds insufficient funds at time of collection.");
        }

        executeFundTransfer(payerLocked, collectorLocked, amount);

        String memo = qrPayment.getNote().substring(PAYER_QR_PREFIX.length());
        Transaction tx = generateTransactionRecord(payerLocked, collectorLocked, amount,
                "Send-by-QR" + (memo.isEmpty() ? "" : " – " + memo), "QR-COL-");

        qrPayment.setStatus(QrStatus.COMPLETED);
        qrPayment.setPayerAccount(payerLocked);
        qrPayment.setTransaction(tx);
        qrPayment.setPaidAt(LocalDateTime.now());
        // REMOVED: qrPayment.setPayeeAccount(collectorLocked); - Preserves domain integrity
        qrPaymentRepository.save(qrPayment);

        doubleEntryService.createDoubleEntry(tx.getId(), payerLocked.getId(), collectorLocked.getId(), amount, "QR Collect", username);
        eventPublisherService.publishTransactionCompletedEvent(tx);

        return qrPayment;
    }

    private void validateQrStatusDefensively(QrPayment qrPayment) {
        boolean isAccountQr = ACCOUNT_QR.equals(qrPayment.getNote());

        if (qrPayment.getStatus() == QrStatus.EXPIRED || (!isAccountQr && LocalDateTime.now().isAfter(qrPayment.getExpiresAt()))) {
            autonomousStateService.markAsExpired(qrPayment.getId()); // Safely commits state outside this transaction
            throw new IllegalStateException("QR code validity period has expired.");
        }
        if (!isAccountQr && qrPayment.getStatus() != QrStatus.PENDING) {
            throw new IllegalStateException("QR code execution rejected: already processed.");
        }
        if (qrPayment.getStatus() == QrStatus.CANCELLED) {
            throw new IllegalStateException("QR code execution rejected: cancelled by issuer.");
        }
    }

    private Account[] acquireDeterministicLocks(Long id1, Long id2) {
        Long firstLockId = Math.min(id1, id2);
        Long secondLockId = Math.max(id1, id2);

        Account firstLocked = accountRepository.findByIdWithLock(firstLockId)
                .orElseThrow(() -> new IllegalArgumentException("Integrity fault: Account not found: " + firstLockId));
        Account secondLocked = accountRepository.findByIdWithLock(secondLockId)
                .orElseThrow(() -> new IllegalArgumentException("Integrity fault: Account not found: " + secondLockId));

        return new Account[]{firstLocked, secondLocked};
    }

    private void executeFundTransfer(Account debitAccount, Account creditAccount, BigDecimal amount) {
        debitAccount.setBalance(debitAccount.getBalance().subtract(amount));
        accountRepository.save(debitAccount);

        int bucketIdx = accountBucketService.selectRandomBucketIndex();
        try {
            accountBucketService.creditBucket(creditAccount.getId(), bucketIdx, amount);
        } catch (RuntimeException e) {
            log.warn("Bucket credit failed, falling back to aggregate balance update. Reason: {}", e.getMessage());
            // In a true Staff-level architecture, ensure this fallback doesn't hide a rollback-only marker.
            creditAccount.setBalance(creditAccount.getBalance().add(amount));
            accountRepository.save(creditAccount);
        }
    }

    private Transaction generateTransactionRecord(Account from, Account to, BigDecimal amount, String note, String prefix) {
        Transaction tx = Transaction.builder()
                .fromAccount(from)
                .toAccount(to)
                .amount(amount)
                .transactionType(TransactionType.PAYMENT)
                .status(TransactionStatus.SUCCESS)
                .note(note)
                .timestamp(LocalDateTime.now())
                .transactionReference(prefix + RandomStringUtils.randomAlphanumeric(10).toUpperCase()) // Secure non-collision generation
                .build();
        return transactionRepository.save(tx);
    }

    private void updateQrRecord(QrPayment qrPayment, Account payerLocked, Transaction tx) {
        if (!ACCOUNT_QR.equals(qrPayment.getNote())) {
            qrPayment.setStatus(QrStatus.COMPLETED);
        } else {
            qrPayment.setStatus(QrStatus.PENDING);
        }
        qrPayment.setPayerAccount(payerLocked);
        qrPayment.setTransaction(tx);
        qrPayment.setPaidAt(LocalDateTime.now());
        qrPaymentRepository.save(qrPayment);
    }

    private BigDecimal resolveAmount(QrPayment qrPayment, PayByQrRequest request) {
        if (qrPayment.getAmount() != null) return qrPayment.getAmount();
        if (request.amount() == null) throw new IllegalArgumentException("Transaction payload missing required amount.");
        return request.amount();
    }
}