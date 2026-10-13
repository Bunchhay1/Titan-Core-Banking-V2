package com.titan.titancorebanking.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.titan.titancorebanking.dto.request.CollectByQrRequest;
import com.titan.titancorebanking.dto.request.GeneratePayerQrRequest;
import com.titan.titancorebanking.dto.request.GenerateQrRequest;
import com.titan.titancorebanking.dto.request.PayByQrRequest;
import com.titan.titancorebanking.dto.response.QrPaymentResponse;
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
        Account payee = accountRepository.findByAccountNumber(request.payeeAccountNumber())
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + request.payeeAccountNumber()));

        if (!payee.getUser().getUsername().equals(username)) {
            throw new SecurityException("You can only generate QR codes for your own accounts.");
        }

        String qrToken = generateUniqueToken();
        int ttl = (request.ttlMinutes() != null) ? request.ttlMinutes() : DEFAULT_TTL_MIN;
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(ttl);

        QrPayment qrPayment = QrPayment.builder()
                .qrCode(qrToken)
                .payeeAccount(payee)
                .amount(request.amount())
                .currency(payee.getCurrency().name())
                .note(request.note())
                .status(QrStatus.PENDING)
                .expiresAt(expiresAt)
                .build();

        qrPaymentRepository.save(qrPayment);
        log.info("QR generated: token={} payee={} amount={}", qrToken, request.payeeAccountNumber(), request.amount());

        String base64Image = renderQrToBase64(qrToken);
        return toResponse(qrPayment, base64Image);
    }

    // =============================================================================
    // 2. PAY BY QR
    // =============================================================================

    @Transactional
    public QrPaymentResponse payByQr(PayByQrRequest request, String username) {
        // Step 1: Pessimistic write lock on the QR token to prevent concurrent double-scans
        QrPayment qrPayment = qrPaymentRepository.findByQrCodeWithLock(request.qrCode())
                .orElseThrow(() -> new IllegalArgumentException("Invalid QR code."));

        boolean isAccountQr = "ACCOUNT_QR".equals(qrPayment.getNote());
        if (qrPayment.getStatus() == QrStatus.EXPIRED ||
                (!isAccountQr && LocalDateTime.now().isAfter(qrPayment.getExpiresAt()))) {
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

        if (!isAccountQr) {
            qrPayment.setStatus(QrStatus.COMPLETED);
        }

        Account payer = accountRepository.findByAccountNumber(request.payerAccountNumber())
                .orElseThrow(() -> new IllegalArgumentException("Payer account not found: " + request.payerAccountNumber()));

        if (!payer.getUser().getUsername().equals(username)) {
            throw new SecurityException("You can only pay from your own accounts.");
        }

        BigDecimal paymentAmount = resolveAmount(qrPayment, request);
        Account payee = qrPayment.getPayeeAccount();

        if (payer.getAccountNumber().equals(payee.getAccountNumber())) {
            throw new IllegalArgumentException("You cannot pay yourself via QR.");
        }

        // Step 2: Deterministic row locking (min(id) -> max(id))
        Long payerId = payer.getId();
        Long payeeId = payee.getId();
        Account payerLocked;
        Account payeeLocked;

        if (payerId < payeeId) {
            payerLocked = accountRepository.findByIdWithLock(payerId)
                    .orElseThrow(() -> new IllegalArgumentException("Payer account not found: " + payerId));
            payeeLocked = accountRepository.findByIdWithLock(payeeId)
                    .orElseThrow(() -> new IllegalArgumentException("Payee account not found: " + payeeId));
        } else {
            payeeLocked = accountRepository.findByIdWithLock(payeeId)
                    .orElseThrow(() -> new IllegalArgumentException("Payee account not found: " + payeeId));
            payerLocked = accountRepository.findByIdWithLock(payerId)
                    .orElseThrow(() -> new IllegalArgumentException("Payer account not found: " + payerId));
        }

        // Step 3: Verify PIN on the locked entity (prevents TOCTOU race)
        if (!passwordEncoder.matches(request.pin(), payerLocked.getUser().getPin())) {
            throw new SecurityException("Invalid PIN.");
        }

        if (payerLocked.getBalance().compareTo(paymentAmount) < 0) {
            throw new IllegalStateException("Insufficient balance.");
        }

        // Step 4: Transfer funds
        payerLocked.setBalance(payerLocked.getBalance().subtract(paymentAmount));
        accountRepository.save(payerLocked);

        int bucketIdx = accountBucketService.selectRandomBucketIndex();
        try {
            accountBucketService.creditBucket(payeeLocked.getId(), bucketIdx, paymentAmount);
        } catch (Exception e) {
            payeeLocked.setBalance(payeeLocked.getBalance().add(paymentAmount));
            accountRepository.save(payeeLocked);
        }

        Transaction tx = Transaction.builder()
                .fromAccount(payerLocked)
                .toAccount(payeeLocked)
                .amount(paymentAmount)
                .transactionType(TransactionType.PAYMENT)
                .status(TransactionStatus.SUCCESS)
                .note("QR Payment - " + (qrPayment.getNote() != null ? qrPayment.getNote() : ""))
                .timestamp(LocalDateTime.now())
                .transactionReference("QR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .build();
        transactionRepository.save(tx);

        if (!isAccountQr) {
            qrPayment.setStatus(QrStatus.COMPLETED);
        } else {
            qrPayment.setStatus(QrStatus.PENDING);
        }
        qrPayment.setPayerAccount(payerLocked);
        qrPayment.setTransaction(tx);
        qrPayment.setPaidAt(LocalDateTime.now());
        qrPaymentRepository.save(qrPayment);

        try {
            eventPublisherService.publishTransactionCompletedEvent(tx);
        } catch (Exception e) {
            log.error("Failed to publish transaction event for QR pay: {}", e.getMessage());
        }

        return toResponse(qrPayment, null);
    }

    // =============================================================================
    // 3. GENERATE PAYER QR
    // =============================================================================

    @Transactional
    public QrPaymentResponse generatePayerQr(GeneratePayerQrRequest request, String username) {
        Account payer = accountRepository.findByAccountNumber(request.payerAccountNumber())
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + request.payerAccountNumber()));

        if (!payer.getUser().getUsername().equals(username)) {
            throw new SecurityException("You can only generate QR codes for your own accounts.");
        }

        if (!passwordEncoder.matches(request.pin(), payer.getUser().getPin())) {
            throw new SecurityException("Invalid PIN.");
        }

        BigDecimal amount = request.amount();
        if (payer.getBalance().compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient balance to generate this QR.");
        }

        String qrToken = generateUniqueToken();
        int ttl = (request.ttlMinutes() != null) ? request.ttlMinutes() : DEFAULT_TTL_MIN;
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(ttl);

        QrPayment qrPayment = QrPayment.builder()
                .qrCode(qrToken)
                .payeeAccount(payer)
                .amount(amount)
                .currency(payer.getCurrency().name())
                .note("PAYER_QR:" + (request.note() != null ? request.note() : ""))
                .status(QrStatus.PENDING)
                .expiresAt(expiresAt)
                .build();

        qrPaymentRepository.save(qrPayment);
        log.info("Payer QR generated: token={} payer={} amount={}", qrToken, request.payerAccountNumber(), amount);

        String base64Image = renderQrToBase64(qrToken);
        return toResponse(qrPayment, base64Image);
    }

    // =============================================================================
    // 4. COLLECT BY QR
    // =============================================================================

    @Transactional
    public QrPaymentResponse collectByQr(CollectByQrRequest request, String username) {
        QrPayment qrPayment = qrPaymentRepository.findByQrCodeWithLock(request.qrCode())
                .orElseThrow(() -> new IllegalArgumentException("Invalid QR code."));

        if (qrPayment.getNote() == null || !qrPayment.getNote().startsWith("PAYER_QR:")) {
            throw new IllegalArgumentException("This QR code is not a Send-by-QR code. Use the pay endpoint instead.");
        }

        if (qrPayment.getStatus() == QrStatus.EXPIRED || LocalDateTime.now().isAfter(qrPayment.getExpiresAt())) {
            qrPayment.setStatus(QrStatus.EXPIRED);
            qrPaymentRepository.save(qrPayment);
            throw new IllegalStateException("QR code has expired.");
        }

        if (qrPayment.getStatus() != QrStatus.PENDING) {
            throw new IllegalStateException("QR code has already been collected.");
        }

        if (qrPayment.getStatus() == QrStatus.CANCELLED) {
            throw new IllegalStateException("QR code has been cancelled.");
        }

        qrPayment.setStatus(QrStatus.COMPLETED);

        Account collector = accountRepository.findByAccountNumber(request.collectorAccountNumber())
                .orElseThrow(() -> new IllegalArgumentException("Collector account not found: " + request.collectorAccountNumber()));

        if (!collector.getUser().getUsername().equals(username)) {
            throw new SecurityException("You can only collect into your own accounts.");
        }

        Account payer = qrPayment.getPayeeAccount();
        if (payer.getAccountNumber().equals(collector.getAccountNumber())) {
            throw new IllegalArgumentException("You cannot collect a QR into the same account.");
        }

        Long payerId = payer.getId();
        Long collectorId = collector.getId();
        Account payerLocked;
        Account collectorLocked;

        if (payerId < collectorId) {
            payerLocked = accountRepository.findByIdWithLock(payerId)
                    .orElseThrow(() -> new IllegalArgumentException("Payer account not found: " + payerId));
            collectorLocked = accountRepository.findByIdWithLock(collectorId)
                    .orElseThrow(() -> new IllegalArgumentException("Collector account not found: " + collectorId));
        } else {
            collectorLocked = accountRepository.findByIdWithLock(collectorId)
                    .orElseThrow(() -> new IllegalArgumentException("Collector account not found: " + collectorId));
            payerLocked = accountRepository.findByIdWithLock(payerId)
                    .orElseThrow(() -> new IllegalArgumentException("Payer account not found: " + payerId));
        }

        BigDecimal amount = qrPayment.getAmount();
        if (payerLocked.getBalance().compareTo(amount) < 0) {
            throw new IllegalStateException("Payer's account no longer has sufficient balance.");
        }

        payerLocked.setBalance(payerLocked.getBalance().subtract(amount));
        accountRepository.save(payerLocked);

        int bucketIdx = accountBucketService.selectRandomBucketIndex();
        try {
            accountBucketService.creditBucket(collectorLocked.getId(), bucketIdx, amount);
        } catch (Exception e) {
            collectorLocked.setBalance(collectorLocked.getBalance().add(amount));
            accountRepository.save(collectorLocked);
        }

        String memo = qrPayment.getNote().substring("PAYER_QR:".length());
        Transaction tx = Transaction.builder()
                .fromAccount(payerLocked)
                .toAccount(collectorLocked)
                .amount(amount)
                .transactionType(TransactionType.PAYMENT)
                .status(TransactionStatus.SUCCESS)
                .note("Send-by-QR" + (memo.isEmpty() ? "" : " - " + memo))
                .timestamp(LocalDateTime.now())
                .transactionReference("QR-SEND-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .build();
        transactionRepository.save(tx);

        qrPayment.setStatus(QrStatus.COMPLETED);
        qrPayment.setPayerAccount(payerLocked);
        qrPayment.setTransaction(tx);
        qrPayment.setPaidAt(LocalDateTime.now());
        qrPayment.setPayeeAccount(collectorLocked);
        qrPaymentRepository.save(qrPayment);

        try {
            eventPublisherService.publishTransactionCompletedEvent(tx);
        } catch (Exception e) {
            log.error("Failed to publish transaction completed event for QR collect: {}", e.getMessage());
        }

        return toResponse(qrPayment, null);
    }

    // =============================================================================
    // 5. CANCEL & HISTORY & ACCOUNT QR
    // =============================================================================

    @Transactional
    public QrPaymentResponse cancelQr(String qrCode, String username) {
        QrPayment qrPayment = qrPaymentRepository.findByQrCode(qrCode)
                .orElseThrow(() -> new IllegalArgumentException("QR code not found."));

        if (!qrPayment.getPayeeAccount().getUser().getUsername().equals(username)) {
            throw new SecurityException("You can only cancel your own QR codes.");
        }

        if (qrPayment.getStatus() != QrStatus.PENDING) {
            throw new IllegalStateException("Only PENDING QR codes can be cancelled. Current status: " + qrPayment.getStatus());
        }

        qrPayment.setStatus(QrStatus.CANCELLED);
        qrPaymentRepository.save(qrPayment);
        return toResponse(qrPayment, null);
    }

    public List<QrPaymentResponse> getQrHistory(String accountNumber, String username) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new IllegalArgumentException("Account not found."));

        if (!account.getUser().getUsername().equals(username)) {
            throw new SecurityException("Access denied.");
        }

        return qrPaymentRepository.findByPayeeAccount_AccountNumberOrderByCreatedAtDesc(accountNumber)
                .stream()
                .map(q -> toResponse(q, null))
                .toList();
    }

    @Transactional
    public QrPaymentResponse getOrCreateAccountQr(String accountNumber, String username) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountNumber));

        if (!account.getUser().getUsername().equals(username)) {
            throw new SecurityException("You can only access QR codes for your own accounts.");
        }

        QrPayment qrPayment = qrPaymentRepository
                .findByPayeeAccount_AccountNumberAndNote(accountNumber, "ACCOUNT_QR")
                .orElseGet(() -> {
                    String qrToken = generateUniqueToken();
                    QrPayment newQr = QrPayment.builder()
                            .qrCode(qrToken)
                            .payeeAccount(account)
                            .amount(null)
                            .currency(account.getCurrency().name())
                            .note("ACCOUNT_QR")
                            .status(QrStatus.PENDING)
                            .expiresAt(LocalDateTime.now().plusYears(100))
                            .build();
                    return qrPaymentRepository.save(newQr);
                });

        String base64Image = renderQrToBase64(qrPayment.getQrCode());
        return toResponse(qrPayment, base64Image);
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void expireStaleQrCodes() {
        int expired = qrPaymentRepository.expireStaleQrCodes(LocalDateTime.now());
        if (expired > 0) {
            log.info("Expired {} stale QR codes.", expired);
        }
    }

    // =============================================================================
    // PRIVATE UTILS
    // =============================================================================

    private String generateUniqueToken() {
        String token;
        do {
            token = UUID.randomUUID().toString().replace("-", "");
        } while (qrPaymentRepository.existsByQrCode(token));
        return token;
    }

    private String renderQrToBase64(String content) {
        try {
            var hints = Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H,
                    EncodeHintType.MARGIN, 2
            );
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, QR_IMAGE_SIZE, QR_IMAGE_SIZE, hints);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            log.error("Failed to render QR image", e);
            throw new RuntimeException("QR image generation failed.", e);
        }
    }

    private BigDecimal resolveAmount(QrPayment qrPayment, PayByQrRequest request) {
        if (qrPayment.getAmount() != null) {
            return qrPayment.getAmount();
        }
        if (request.amount() == null) {
            throw new IllegalArgumentException("This QR code requires you to specify an amount.");
        }
        return request.amount();
    }

    private QrPaymentResponse toResponse(QrPayment q, String base64Image) {
        return new QrPaymentResponse(
                q.getId(),
                q.getQrCode(),
                base64Image,
                q.getStatus().name(),
                q.getAmount(),
                q.getCurrency(),
                q.getPayeeAccount().getAccountNumber(),
                q.getPayerAccount() != null ? q.getPayerAccount().getAccountNumber() : null,
                q.getNote(),
                q.getCreatedAt(),
                q.getExpiresAt(),
                q.getPaidAt(),
                q.getTransaction() != null ? q.getTransaction().getId() : null
        );
    }
}