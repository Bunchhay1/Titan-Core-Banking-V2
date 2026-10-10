package com.titan.titancorebanking.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.titan.titancorebanking.dto.request.*;
import com.titan.titancorebanking.dto.response.QrPaymentResponse;
import com.titan.titancorebanking.exception.AccountNotFoundException;
import com.titan.titancorebanking.exception.UnauthorizedAccessException;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.QrPayment;
import com.titan.titancorebanking.model.QrPayment.QrStatus;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.QrPaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;

/**
 * Orchestrates QR operations, Cryptography (Bcrypt), and heavy Image Rendering
 * outside of strict database transaction boundaries.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QrPaymentOrchestratorService {

    @Value("${qr.image.size:300}")
    private int qrImageSize;

    @Value("${qr.default-ttl-minutes:15}")
    private int defaultTtlMin;

    private final AccountRepository accountRepository;
    private final QrPaymentRepository qrPaymentRepository;
    private final PasswordEncoder passwordEncoder;
    private final QrPaymentExecutionService executionService;
    private final SecureRandom secureRandom = new SecureRandom();

    // =============================================================================
    // 1. GENERATE RECEIVER QR
    // =============================================================================
    public QrPaymentResponse generateQr(GenerateQrRequest request, String username) {
        Account payee = getValidatedAccount(request.payeeAccountNumber(), username);
        String qrToken = generateSecureToken();
        int ttl = request.ttlMinutes() != null ? request.ttlMinutes() : defaultTtlMin;

        QrPayment qrPayment = QrPayment.builder()
                .qrCode(qrToken)
                .payeeAccount(payee)
                .amount(request.amount())
                .currency(payee.getCurrency().name())
                .note(request.note())
                .status(QrStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(ttl))
                .build();

        qrPayment = qrPaymentRepository.save(qrPayment);
        String base64Image = renderQrToBase64(qrToken);

        return toResponse(qrPayment, base64Image);
    }

    // =============================================================================
    // 2. PAY BY QR (Atomic Execution)
    // =============================================================================
    public QrPaymentResponse payByQr(PayByQrRequest request, String username) {
        Account payer = getValidatedAccount(request.payerAccountNumber(), username);
        validatePin(request.pin(), payer.getUser().getPin());

        QrPayment completedQr = executionService.executePayByQrAtomically(request, payer.getId(), username);
        return toResponse(completedQr, null);
    }

    // =============================================================================
    // 3. GENERATE PAYER QR
    // =============================================================================
    public QrPaymentResponse generatePayerQr(GeneratePayerQrRequest request, String username) {
        Account payer = getValidatedAccount(request.payerAccountNumber(), username);
        validatePin(request.pin(), payer.getUser().getPin());

        String qrToken = generateSecureToken();
        int ttl = request.ttlMinutes() != null ? request.ttlMinutes() : defaultTtlMin;

        QrPayment qrPayment = QrPayment.builder()
                .qrCode(qrToken)
                // TODO (Domain Model Fix): This requires a DB migration. Do not misuse payeeAccount for payers.
                // It should be: .initiatorAccount(payer).qrType(QrType.PAYER)
                .payeeAccount(payer)
                .amount(request.amount())
                .currency(payer.getCurrency().name())
                .note("PAYER_QR:" + (request.note() != null ? request.note() : ""))
                .status(QrStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(ttl))
                .build();

        qrPayment = qrPaymentRepository.save(qrPayment);
        String base64Image = renderQrToBase64(qrToken);

        return toResponse(qrPayment, base64Image);
    }

    // =============================================================================
    // 4. COLLECT BY QR
    // =============================================================================
    public QrPaymentResponse collectByQr(CollectByQrRequest request, String username) {
        Account collector = getValidatedAccount(request.collectorAccountNumber(), username);

        QrPayment completedQr = executionService.executeCollectByQrAtomically(request, collector.getId(), username);
        return toResponse(completedQr, null);
    }

    // =============================================================================
    // INTERNAL ABSTRACTIONS & DEFENSIVE GUARDS
    // =============================================================================

    private Account getValidatedAccount(String accountNumber, String username) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountNumber));

        if (!account.getUser().getUsername().equals(username)) {
            log.warn("Unauthorized QR action attempt by user [{}] on account [{}]", username, accountNumber);
            throw new UnauthorizedAccessException("You lack permissions to operate on this account.");
        }
        return account;
    }

    private void validatePin(String rawPin, String encodedPin) {
        if (!passwordEncoder.matches(rawPin, encodedPin)) {
            throw new UnauthorizedAccessException("Cryptographic validation failed: Invalid PIN.");
        }
    }

    private String generateSecureToken() {
        byte[] tokenBytes = new byte[24];
        secureRandom.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    private String renderQrToBase64(String content) {
        try {
            var hints = Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H,
                    EncodeHintType.MARGIN, 2
            );
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, qrImageSize, qrImageSize, hints);

            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                MatrixToImageWriter.writeToStream(matrix, "PNG", out);
                return Base64.getEncoder().encodeToString(out.toByteArray());
            }
        } catch (Exception e) {
            log.error("Failed to generate QR Code matrix", e);
            throw new RuntimeException("QR rendering engine failure.", e);
        }
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