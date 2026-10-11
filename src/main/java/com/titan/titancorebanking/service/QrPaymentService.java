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
    private final DoubleEntryService doubleEntryService;

    @Transactional
    public QrPaymentResponse generateQr(GenerateQrRequest request, String username) {
        Account payee = accountRepository.findByAccountNumber(request.payeeAccountNumber())
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + request.payeeAccountNumber()));

        if (!payee.getUser().getUsername().equals(username)) {
            throw new SecurityException("You can only generate QR codes for your own accounts.");
        }

        String qrToken = generateUniqueToken();
        int ttl = (request.ttlMinutes() != null) ? request.ttlMinutes() : DEFAULT_TTL_MIN;

        QrPayment qrPayment = QrPayment.builder()
                .qrCode(qrToken)
                .payeeAccount(payee)
                .amount(request.amount())
                .currency(payee.getCurrency().name())
                .note(request.note())
                .status(QrStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(ttl))
                .build();

        qrPaymentRepository.save(qrPayment);
        return toResponse(qrPayment, renderQrToBase64(qrToken));
    }

    @Transactional
    public QrPaymentResponse payByQr(PayByQrRequest request, String username) {
        QrPayment qrPayment = qrPaymentRepository.findByQrCodeWithLock(request.qrCode())
                .orElseThrow(() -> new IllegalArgumentException("Invalid QR code."));

        boolean isAccountQr = "ACCOUNT_QR".equals(qrPayment.getNote());
        validateQrState(qrPayment, isAccountQr);

        Account payer = accountRepository.findByAccountNumber(request.payerAccountNumber())
                .orElseThrow(() -> new IllegalArgumentException("Payer account not found."));

        if (!payer.getUser().getUsername().equals(username)) {
            throw new SecurityException("You can only pay from your own accounts.");
        }

        if (!passwordEncoder.matches(request.pin(), payer.getUser().getPin())) {
            throw new SecurityException("Invalid PIN.");
        }

        BigDecimal paymentAmount = resolveAmount(qrPayment, request);
        Account payee = qrPayment.getPayeeAccount();

        if (payer.getAccountNumber().equals(payee.getAccountNumber())) {
            throw new IllegalArgumentException("You cannot pay yourself via QR.");
        }

        Long firstLockId = Math.min(payer.getId(), payee.getId());
        Long secondLockId = Math.max(payer.getId(), payee.getId());

        Account firstLocked = accountRepository.findByIdWithLock(firstLockId).orElseThrow();
        Account secondLocked = accountRepository.findByIdWithLock(secondLockId).orElseThrow();

        Account payerLocked = payer.getId().equals(firstLocked.getId()) ? firstLocked : secondLocked;
        Account payeeLocked = payee.getId().equals(firstLocked.getId()) ? firstLocked : secondLocked;

        if (payerLocked.getBalance().compareTo(paymentAmount) < 0) {
            throw new IllegalStateException("Insufficient balance.");
        }

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
                .note("QR Payment " + (qrPayment.getNote() != null ? qrPayment.getNote() : ""))
                .timestamp(LocalDateTime.now())
                .transactionReference("QR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .build();
        transactionRepository.save(tx);

        doubleEntryService.createDoubleEntry(
                tx.getId(), payerLocked.getId(), payeeLocked.getId(), paymentAmount, tx.getNote(), username);

        qrPayment.setStatus(isAccountQr ? QrStatus.PENDING : QrStatus.COMPLETED);
        qrPayment.setPayerAccount(payerLocked);
        qrPayment.setTransaction(tx);
        qrPayment.setPaidAt(LocalDateTime.now());
        qrPaymentRepository.save(qrPayment);

        eventPublisherService.publishTransactionCompletedEvent(tx);
        return toResponse(qrPayment, null);
    }

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

        if (payer.getBalance().compareTo(request.amount()) < 0) {
            throw new IllegalStateException("Insufficient balance to generate this QR.");
        }

        String qrToken = generateUniqueToken();
        int ttl = (request.ttlMinutes() != null) ? request.ttlMinutes() : DEFAULT_TTL_MIN;

        QrPayment qrPayment = QrPayment.builder()
                .qrCode(qrToken)
                .payeeAccount(payer)
                .amount(request.amount())
                .currency(payer.getCurrency().name())
                .note("PAYER_QR:" + (request.note() != null ? request.note() : ""))
                .status(QrStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(ttl))
                .build();

        qrPaymentRepository.save(qrPayment);
        return toResponse(qrPayment, renderQrToBase64(qrToken));
    }

    @Transactional
    public QrPaymentResponse collectByQr(CollectByQrRequest request, String username) {
        QrPayment qrPayment = qrPaymentRepository.findByQrCodeWithLock(request.qrCode())
                .orElseThrow(() -> new IllegalArgumentException("Invalid QR code."));

        if (qrPayment.getNote() == null || !qrPayment.getNote().startsWith("PAYER_QR:")) {
            throw new IllegalArgumentException("This QR code is not a Send-by-QR code.");
        }

        validateQrState(qrPayment, false);

        Account collector = accountRepository.findByAccountNumber(request.collectorAccountNumber())
                .orElseThrow(() -> new IllegalArgumentException("Collector account not found"));

        if (!collector.getUser().getUsername().equals(username)) {
            throw new SecurityException("You can only collect into your own accounts.");
        }

        Account payer = qrPayment.getPayeeAccount();

        if (payer.getAccountNumber().equals(collector.getAccountNumber())) {
            throw new IllegalArgumentException("You cannot collect a QR into the same account.");
        }

        Long firstLockId = Math.min(payer.getId(), collector.getId());
        Long secondLockId = Math.max(payer.getId(), collector.getId());

        Account firstLocked = accountRepository.findByIdWithLock(firstLockId).orElseThrow();
        Account secondLocked = accountRepository.findByIdWithLock(secondLockId).orElseThrow();

        Account payerLocked = payer.getId().equals(firstLocked.getId()) ? firstLocked : secondLocked;
        Account collectorLocked = collector.getId().equals(firstLocked.getId()) ? firstLocked : secondLocked;

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

        Transaction tx = Transaction.builder()
                .fromAccount(payerLocked)
                .toAccount(collectorLocked)
                .amount(amount)
                .transactionType(TransactionType.PAYMENT)
                .status(TransactionStatus.SUCCESS)
                .note("Send-by-QR")
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

        eventPublisherService.publishTransactionCompletedEvent(tx);
        return toResponse(qrPayment, null);
    }

    @Transactional
    public QrPaymentResponse cancelQr(String qrCode, String username) {
        QrPayment qrPayment = qrPaymentRepository.findByQrCode(qrCode)
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

    @Transactional(readOnly = true)
    public List<QrPaymentResponse> getQrHistory(String accountNumber, String username) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new IllegalArgumentException("Account not found."));

        if (!account.getUser().getUsername().equals(username)) {
            throw new SecurityException("Access denied.");
        }

        return qrPaymentRepository.findByPayeeAccount_AccountNumberOrderByCreatedAtDesc(accountNumber)
                .stream().map(q -> toResponse(q, null)).toList();
    }

    @Transactional
    public QrPaymentResponse getOrCreateAccountQr(String accountNumber, String username) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));

        if (!account.getUser().getUsername().equals(username)) {
            throw new SecurityException("You can only access QR codes for your own accounts.");
        }

        QrPayment qrPayment = qrPaymentRepository
                .findByPayeeAccount_AccountNumberAndNote(accountNumber, "ACCOUNT_QR")
                .orElseGet(() -> {
                    QrPayment newQr = QrPayment.builder()
                            .qrCode(generateUniqueToken())
                            .payeeAccount(account)
                            .currency(account.getCurrency().name())
                            .note("ACCOUNT_QR")
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
        qrPaymentRepository.expireStaleQrCodes(LocalDateTime.now());
    }

    private void validateQrState(QrPayment qrPayment, boolean isAccountQr) {
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
            throw new RuntimeException("QR image generation failed.", e);
        }
    }

    private BigDecimal resolveAmount(QrPayment qrPayment, PayByQrRequest request) {
        if (qrPayment.getAmount() != null) return qrPayment.getAmount();
        if (request.amount() == null) throw new IllegalArgumentException("This QR code requires an amount.");
        return request.amount();
    }

    private QrPaymentResponse toResponse(QrPayment q, String base64Image) {
        return new QrPaymentResponse(
                q.getId(), q.getQrCode(), base64Image, q.getStatus().name(),
                q.getAmount(), q.getCurrency(), q.getPayeeAccount().getAccountNumber(),
                q.getPayerAccount() != null ? q.getPayerAccount().getAccountNumber() : null,
                q.getNote(), q.getCreatedAt(), q.getExpiresAt(), q.getPaidAt(),
                q.getTransaction() != null ? q.getTransaction().getId() : null
        );
    }
}