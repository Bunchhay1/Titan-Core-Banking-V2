package com.titan.titancorebanking.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.titan.titancorebanking.dto.request.GenerateQrRequest;
import com.titan.titancorebanking.dto.request.GeneratePayerQrRequest;
import com.titan.titancorebanking.dto.request.CollectByQrRequest;
import com.titan.titancorebanking.dto.request.PayByQrRequest;
import com.titan.titancorebanking.dto.response.QrPaymentResponse;
import com.titan.titancorebanking.enums.TransactionStatus;
import com.titan.titancorebanking.enums.TransactionType;
import com.titan.titancorebanking.exception.InvalidPinException;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.QrPayment;
import com.titan.titancorebanking.model.QrPayment.QrStatus;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.QrPaymentRepository;
import com.titan.titancorebanking.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class QrPaymentService {

    private static final int QR_IMAGE_SIZE = 300;
    private static final int DEFAULT_TTL_MIN = 15;
    private static final String PAYER_QR_PREFIX = "PAYER_QR:";
    private static final String ACCOUNT_QR_NOTE = "ACCOUNT_QR";

    private final AccountRepository accountRepository;
    private final QrPaymentRepository qrPaymentRepository;
    private final TransactionRepository transactionRepository;
    private final PasswordEncoder passwordEncoder;
    private final EventPublisherService eventPublisherService;
    private final AccountBucketService accountBucketService;

    // =============================================================================
    // 1. GENERATE QR
    // =============================================================================
    @Transactional
    public QrPaymentResponse generateQr(GenerateQrRequest request, String username) {
        var payee = getAccountAndValidateOwnership(request.payeeAccountNumber(), username);
        var qrToken = generateUniqueToken();
        var ttl = request.ttlMinutes() != null ? request.ttlMinutes() : DEFAULT_TTL_MIN;

        var qrPayment = QrPayment.builder()
                .qrCode(qrToken)
                .payeeAccount(payee)
                .amount(request.amount())
                .currency(payee.getCurrency().name())
                .note(request.note())
                .status(QrStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(ttl))
                .build();

        qrPaymentRepository.save(qrPayment);
        log.info("QR generated: token={} payee={} amount={}", qrToken, request.payeeAccountNumber(), request.amount());

        return toResponse(qrPayment, renderQrToBase64(qrToken));
    }

    // =============================================================================
    // 2. PAY BY QR
    // =============================================================================
    @Transactional
    public QrPaymentResponse payByQr(PayByQrRequest request, String username) {
        var qrPayment = getQrWithPessimisticLock(request.qrCode());

        // [MODIFIED] Extracted QR status and expiry validation logic to a helper method.
        // ផ្តាច់ការត្រួតពិនិត្យ ស្ថានភាព (Status) និងថ្ងៃផុតកំណត់ (Expiry) ទៅ Helper Method ដើម្បងាយស្រួលប្រើឡើងវិញ។
        validateQrIsExecutable(qrPayment, ACCOUNT_QR_NOTE.equals(qrPayment.getNote()));

        var payer = getAccountAndValidateOwnership(request.payerAccountNumber(), username);
        validatePin(payer, request.pin());

        var paymentAmount = resolveAmount(qrPayment, request);
        var payee = qrPayment.getPayeeAccount();

        if (payer.getAccountNumber().equals(payee.getAccountNumber())) {
            throw new IllegalArgumentException("You cannot pay yourself via QR.");
        }

        // [MODIFIED] Extracted the core money movement and event publishing to avoid duplication with collectByQr.
        // បង្រួមកូដកាត់លុយ និងបោះ Kafka Event ទៅជាមួយគ្នា ដើម្បីកុំអោយសរសេរកូដជាន់គ្នា (DRY Principle)។
        var tx = executeFundsTransfer(payer, payee, paymentAmount, "QR Payment " + (qrPayment.getNote() != null ? qrPayment.getNote() : ""), "QR-");

        qrPayment.setStatus(ACCOUNT_QR_NOTE.equals(qrPayment.getNote()) ? QrStatus.PENDING : QrStatus.COMPLETED);
        qrPayment.setPayerAccount(payer);
        qrPayment.setTransaction(tx);
        qrPayment.setPaidAt(LocalDateTime.now());
        qrPaymentRepository.save(qrPayment);

        log.info("QR payment completed: qrCode={} payer={} payee={} amount={}", request.qrCode(), payer.getAccountNumber(), payee.getAccountNumber(), paymentAmount);
        return toResponse(qrPayment, null);
    }

    // =============================================================================
    // 3. GENERATE PAYER QR
    // =============================================================================
    @Transactional
    public QrPaymentResponse generatePayerQr(GeneratePayerQrRequest request, String username) {
        var payer = getAccountAndValidateOwnership(request.payerAccountNumber(), username);
        validatePin(payer, request.pin());

        if (payer.getBalance().compareTo(request.amount()) < 0) {
            throw new IllegalStateException("Insufficient balance to generate this QR.");
        }

        var qrToken = generateUniqueToken();
        var ttl = request.ttlMinutes() != null ? request.ttlMinutes() : DEFAULT_TTL_MIN;

        // [MODIFIED] Left a technical debt warning. Storing payer in payeeAccount is a domain model violation.
        // ចំណាំ: ការផ្ទុកគណនីអ្នកបង់ប្រាក់ (Payer) ទៅក្នុង Column `payeeAccount` គឺជាការប្រើប្រាស់ខុសទម្រង់ Database Structure ពិតប្រាកដ។ គួរជួសជុលវានៅកម្រិត Entity ពេលក្រោយ។
        var qrPayment = QrPayment.builder()
                .qrCode(qrToken)
                .payeeAccount(payer)
                .amount(request.amount())
                .currency(payer.getCurrency().name())
                .note(PAYER_QR_PREFIX + (request.note() != null ? request.note() : ""))
                .status(QrStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(ttl))
                .build();

        qrPaymentRepository.save(qrPayment);
        log.info("Payer QR generated: token={} payer={} amount={}", qrToken, request.payerAccountNumber(), request.amount());

        return toResponse(qrPayment, renderQrToBase64(qrToken));
    }

    // =============================================================================
    // 4. COLLECT BY QR
    // =============================================================================
    @Transactional
    public QrPaymentResponse collectByQr(CollectByQrRequest request, String username) {
        var qrPayment = getQrWithPessimisticLock(request.qrCode());

        if (qrPayment.getNote() == null || !qrPayment.getNote().startsWith(PAYER_QR_PREFIX)) {
            throw new IllegalArgumentException("This QR code is not a Send-by-QR code.");
        }

        validateQrIsExecutable(qrPayment, false);

        var collector = getAccountAndValidateOwnership(request.collectorAccountNumber(), username);
        var payer = qrPayment.getPayeeAccount(); // Payer is stored in payeeAccount slot

        if (payer.getAccountNumber().equals(collector.getAccountNumber())) {
            throw new IllegalArgumentException("You cannot collect a QR into the same account.");
        }

        var amount = qrPayment.getAmount();
        String memo = qrPayment.getNote().substring(PAYER_QR_PREFIX.length());

        // [MODIFIED] Reuses the centralized fund transfer logic.
        // ប្រើប្រាស់ Function `executeFundsTransfer` ដែលបានទាញចេញ ដើម្បីអនុវត្តការកាត់លុយ និងបោះ Event កុំអោយសរសេរកូដសារថ្មី។
        var tx = executeFundsTransfer(payer, collector, amount, "Send-by-QR" + (memo.isEmpty() ? "" : " " + memo), "QR-SEND-");

        qrPayment.setStatus(QrStatus.COMPLETED);
        qrPayment.setPayerAccount(payer);
        qrPayment.setPayeeAccount(collector); // Swap back for correct response mapping
        qrPayment.setTransaction(tx);
        qrPayment.setPaidAt(LocalDateTime.now());
        qrPaymentRepository.save(qrPayment);

        log.info("Payer QR collected: qrCode={} payer={} collector={} amount={}", request.qrCode(), payer.getAccountNumber(), collector.getAccountNumber(), amount);
        return toResponse(qrPayment, null);
    }

    // =============================================================================
    // 5. CANCEL QR
    // =============================================================================
    @Transactional
    public QrPaymentResponse cancelQr(String qrCode, String username) {
        var qrPayment = qrPaymentRepository.findByQrCode(qrCode)
                .orElseThrow(() -> new IllegalArgumentException("QR code not found."));

        if (!qrPayment.getPayeeAccount().getUser().getUsername().equals(username)) {
            throw new SecurityException("You can only cancel your own QR codes.");
        }

        if (qrPayment.getStatus() != QrStatus.PENDING) {
            throw new IllegalStateException("Only PENDING QR codes can be cancelled.");
        }

        qrPayment.setStatus(QrStatus.CANCELLED);
        qrPaymentRepository.save(qrPayment);
        return toResponse(qrPayment, null);
    }

    // =============================================================================
    // 6. GET QR HISTORY & PERMANENT QR
    // =============================================================================
    public List<QrPaymentResponse> getQrHistory(String accountNumber, String username) {
        getAccountAndValidateOwnership(accountNumber, username);
        return qrPaymentRepository.findByPayeeAccount_AccountNumberOrderByCreatedAtDesc(accountNumber)
                .stream().map(q -> toResponse(q, null)).toList();
    }

    @Transactional
    public QrPaymentResponse getOrCreateAccountQr(String accountNumber, String username) {
        var account = getAccountAndValidateOwnership(accountNumber, username);

        var qrPayment = qrPaymentRepository.findByPayeeAccount_AccountNumberAndNote(accountNumber, ACCOUNT_QR_NOTE)
                .orElseGet(() -> {
                    var newQr = QrPayment.builder()
                            .qrCode(generateUniqueToken())
                            .payeeAccount(account)
                            .currency(account.getCurrency().name())
                            .note(ACCOUNT_QR_NOTE)
                            .status(QrStatus.PENDING)
                            .expiresAt(LocalDateTime.now().plusYears(100))
                            .build();
                    return qrPaymentRepository.save(newQr);
                });

        return toResponse(qrPayment, renderQrToBase64(qrPayment.getQrCode()));
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void expireStaleQrCodes() {
        int expired = qrPaymentRepository.expireStaleQrCodes(LocalDateTime.now());
        if (expired > 0) log.info("Expired {} stale QR codes.", expired);
    }

    // =============================================================================
    // PRIVATE HELPERS (Clean Code Extractions)
    // =============================================================================

    private QrPayment getQrWithPessimisticLock(String qrCode) {
        return qrPaymentRepository.findByQrCodeWithLock(qrCode)
                .orElseThrow(() -> new IllegalArgumentException("Invalid QR code."));
    }

    private Account getAccountAndValidateOwnership(String accountNumber, String username) {
        var account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountNumber));
        if (!account.getUser().getUsername().equals(username)) {
            throw new SecurityException("Access denied: You do not own account " + accountNumber);
        }
        return account;
    }

    private void validatePin(Account account, String pin) {
        if (!passwordEncoder.matches(pin, account.getUser().getPin())) {
            throw new InvalidPinException("Invalid PIN.");
        }
    }

    private void validateQrIsExecutable(QrPayment qrPayment, boolean isAccountQr) {
        if (qrPayment.getStatus() == QrStatus.EXPIRED || (!isAccountQr && LocalDateTime.now().isAfter(qrPayment.getExpiresAt()))) {
            qrPayment.setStatus(QrStatus.EXPIRED);
            qrPaymentRepository.save(qrPayment);
            throw new IllegalStateException("QR code has expired.");
        }
        if (!isAccountQr && qrPayment.getStatus() != QrStatus.PENDING) {
            throw new IllegalStateException("QR code has already been used.");
        }
        if (qrPayment.getStatus() == QrStatus.CANCELLED) {
            throw new IllegalStateException("QR code has been cancelled.");
        }
    }

    // [MODIFIED] Centralized money movement with deterministic locking.
    // ប្រមូលផ្តុំ Logic នៃការកាត់លុយ និងការបោះ Event មកកន្លែងតែមួយ ដើម្បីអោយកូដមានរបៀបរៀបរយ។
    private Transaction executeFundsTransfer(Account payer, Account payee, BigDecimal amount, String note, String txPrefix) {
        Long payerId = payer.getId();
        Long payeeId = payee.getId();
        Account payerLocked, payeeLocked;

        // Deterministic locking
        if (payerId < payeeId) {
            payerLocked = accountRepository.findByIdWithLock(payerId).orElseThrow();
            payeeLocked = accountRepository.findByIdWithLock(payeeId).orElseThrow();
        } else {
            payeeLocked = accountRepository.findByIdWithLock(payeeId).orElseThrow();
            payerLocked = accountRepository.findByIdWithLock(payerId).orElseThrow();
        }

        if (payerLocked.getBalance().compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient balance.");
        }

        payerLocked.setBalance(payerLocked.getBalance().subtract(amount));
        accountRepository.save(payerLocked);

        try {
            accountBucketService.creditBucket(payeeLocked.getId(), accountBucketService.selectRandomBucketIndex(), amount);
        } catch (Exception e) {
            payeeLocked.setBalance(payeeLocked.getBalance().add(amount));
            accountRepository.save(payeeLocked);
        }

        var tx = Transaction.builder()
                .fromAccount(payerLocked)
                .toAccount(payeeLocked)
                .amount(amount)
                .transactionType(TransactionType.PAYMENT)
                .status(TransactionStatus.SUCCESS)
                .note(note)
                .timestamp(LocalDateTime.now())
                .transactionReference(txPrefix + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .build();
        transactionRepository.save(tx);

        try {
            eventPublisherService.publishTransactionCompletedEvent(tx);
        } catch (Exception e) {
            log.error("Failed to publish transaction completed event for QR pay TX ID {}: {}", tx.getId(), e.getMessage());
        }
        return tx;
    }

    private String generateUniqueToken() {
        String token;
        do {
            token = UUID.randomUUID().toString().replace("-", "");
        } while (qrPaymentRepository.existsByQrCode(token));
        return token;
    }

    private String renderQrToBase64(String content) {
        try {
            var hints = Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H, EncodeHintType.MARGIN, 2);
            var matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, QR_IMAGE_SIZE, QR_IMAGE_SIZE, hints);
            var out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            log.error("Failed to render QR image", e);
            throw new RuntimeException("QR image generation failed.", e);
        }
    }

    private BigDecimal resolveAmount(QrPayment qrPayment, PayByQrRequest request) {
        if (qrPayment.getAmount() != null) return qrPayment.getAmount();
        if (request.amount() == null) throw new IllegalArgumentException("This QR code requires you to specify an amount.");
        return request.amount();
    }

    private QrPaymentResponse toResponse(QrPayment q, String base64Image) {
        return new QrPaymentResponse(q.getId(), q.getQrCode(), base64Image, q.getStatus().name(),
                q.getAmount(), q.getCurrency(), q.getPayeeAccount().getAccountNumber(),
                q.getPayerAccount() != null ? q.getPayerAccount().getAccountNumber() : null,
                q.getNote(), q.getCreatedAt(), q.getExpiresAt(), q.getPaidAt(),
                q.getTransaction() != null ? q.getTransaction().getId() : null);
    }
}