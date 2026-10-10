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

/**
 * ✅ TITAN QR Payment Service
 *
 * Two operations:
 *  1. generateQr()  – creates a QrPayment record, renders ZXing QR PNG, returns base64 image
 *  2. payByQr()     – validates token, deducts payer, credits payee, records Transaction
 *
 * Scheduled task: expireStaleQrCodes() runs every minute to flip PENDING → EXPIRED.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QrPaymentService {

    // ── Constants ────────────────────────────────────────────────────────────────
    private static final int QR_IMAGE_SIZE    = 300;   // pixels
    private static final int DEFAULT_TTL_MIN  = 15;    // minutes

    // ── Dependencies ─────────────────────────────────────────────────────────────
    private final AccountRepository       accountRepository;
    private final QrPaymentRepository     qrPaymentRepository;
    private final TransactionRepository   transactionRepository;
    private final PasswordEncoder         passwordEncoder;
    private final EventPublisherService   eventPublisherService;
    private final AccountBucketService    accountBucketService;

    // =============================================================================
    // 1. GENERATE QR
    // =============================================================================

    /**
     * Generates a QR code for receiving a payment.
     * Called by the payee (account owner who wants to receive money).
     *
     * @param request  GenerateQrRequest (payeeAccountNumber, optional amount, note, ttlMinutes)
     * @param username authenticated user's username (for ownership check)
     * @return QrPaymentResponse including base64-encoded PNG of the QR image
     */
    @Transactional
    public QrPaymentResponse generateQr(GenerateQrRequest request, String username) {
        // 1️⃣  Resolve payee account and verify ownership
        Account payee = accountRepository
                .findByAccountNumber(request.payeeAccountNumber())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Account not found: " + request.payeeAccountNumber()));

        if (!payee.getUser().getUsername().equals(username)) {
            throw new SecurityException("You can only generate QR codes for your own accounts.");
        }

        // 2️⃣  Build a unique QR token (collision-safe)
        String qrToken = generateUniqueToken();

        // 3️⃣  Determine expiry
        int ttl = (request.ttlMinutes() != null) ? request.ttlMinutes() : DEFAULT_TTL_MIN;
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(ttl);

        // 4️⃣  Persist the QR record
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
        log.info("✅ QR generated: token={} payee={} amount={}", qrToken,
                request.payeeAccountNumber(), request.amount());

        // 5️⃣  Render QR image and return response
        String base64Image = renderQrToBase64(qrToken);
        return toResponse(qrPayment, base64Image);
    }

    // =============================================================================
    // 2. PAY BY QR
    // =============================================================================

    /**
     * Processes a payment initiated by scanning a QR code.
     * Called by the payer.
     *
     * @param request  PayByQrRequest (qrCode token, payerAccountNumber, amount, pin)
     * @param username authenticated user's username
     * @return QrPaymentResponse with status COMPLETED and settled transaction ID
     */
    @Transactional
    public QrPaymentResponse payByQr(PayByQrRequest request, String username) {
        // 1️⃣  Acquire pessimistic write lock on the QR entity first to prevent double-spend
        QrPayment qrPayment = qrPaymentRepository.findByQrCodeWithLock(request.qrCode())
                .orElseThrow(() -> new IllegalArgumentException("Invalid QR code."));

        // 2️⃣  Check QR status and expiry
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

        // Mark status as COMPLETED immediately for non-permanent QRs
        if (!isAccountQr) {
            qrPayment.setStatus(QrStatus.COMPLETED);
        }

        // 3️⃣  Resolve payer account and verify ownership
        Account payer = accountRepository
                .findByAccountNumber(request.payerAccountNumber())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Payer account not found: " + request.payerAccountNumber()));

        if (!payer.getUser().getUsername().equals(username)) {
            throw new SecurityException("You can only pay from your own accounts.");
        }

        // 4️⃣  Validate PIN
        if (!passwordEncoder.matches(request.pin(), payer.getUser().getPin())) {
            throw new SecurityException("Invalid PIN.");
        }

        // 5️⃣  Determine payment amount
        BigDecimal paymentAmount = resolveAmount(qrPayment, request);

        // 6️⃣  Guard: payer cannot pay themselves
        Account payee = qrPayment.getPayeeAccount();
        if (payer.getAccountNumber().equals(payee.getAccountNumber())) {
            throw new IllegalArgumentException("You cannot pay yourself via QR.");
        }

        // 7️⃣  Deterministic row-level locking (min(id) -> max(id)) to guarantee deadlock-free execution
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

        // 8️⃣  Balance check on locked entity
        if (payerLocked.getBalance().compareTo(paymentAmount) < 0) {
            throw new IllegalStateException("Insufficient balance.");
        }

        // 9️⃣  Move funds (Debit Payer, Credit Payee Partitioned Bucket)
        payerLocked.setBalance(payerLocked.getBalance().subtract(paymentAmount));
        accountRepository.save(payerLocked);

        int bucketIdx = accountBucketService.selectRandomBucketIndex();
        try {
            accountBucketService.creditBucket(payeeLocked.getId(), bucketIdx, paymentAmount);
        } catch (Exception e) {
            payeeLocked.setBalance(payeeLocked.getBalance().add(paymentAmount));
            accountRepository.save(payeeLocked);
        }

        // 🔟  Record Transaction
        Transaction tx = Transaction.builder()
                .fromAccount(payerLocked)
                .toAccount(payeeLocked)
                .amount(paymentAmount)
                .transactionType(TransactionType.PAYMENT)
                .status(TransactionStatus.SUCCESS)
                .note("QR Payment – " + (qrPayment.getNote() != null ? qrPayment.getNote() : ""))
                .timestamp(LocalDateTime.now())
                .transactionReference("QR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .build();
        transactionRepository.save(tx);

        // 1️⃣1️⃣ Update QR record
        if (!isAccountQr) {
            qrPayment.setStatus(QrStatus.COMPLETED);
        } else {
            qrPayment.setStatus(QrStatus.PENDING);
        }
        qrPayment.setPayerAccount(payerLocked);
        qrPayment.setTransaction(tx);
        qrPayment.setPaidAt(LocalDateTime.now());
        qrPaymentRepository.save(qrPayment);

        // 1️⃣2️⃣ Publish Event via Outbox/Kafka for Real-time Notifications
        try {
            eventPublisherService.publishTransactionCompletedEvent(tx);
        } catch (Exception e) {
            log.error("Failed to publish transaction completed event for QR pay TX ID {}: {}", tx.getId(), e.getMessage());
        }

        log.info("✅ QR payment completed: qrCode={} payer={} payee={} amount={}",
                request.qrCode(), payerLocked.getAccountNumber(),
                payeeLocked.getAccountNumber(), paymentAmount);

        return toResponse(qrPayment, null);
    }

    // =============================================================================
    // 3. GENERATE PAYER QR  (Send by QR — payer pre-authorises a payment)
    // =============================================================================

    /**
     * Payer (Account A) generates a QR code pre-authorised with their PIN.
     * Anyone who scans and calls /collect will receive the money into their account.
     *
     * @param request  GeneratePayerQrRequest (payerAccountNumber, amount, pin, note, ttlMinutes)
     * @param username authenticated user's username
     * @return QrPaymentResponse including base64 QR image
     */
    @Transactional
    public QrPaymentResponse generatePayerQr(GeneratePayerQrRequest request, String username) {
        // 1️⃣  Resolve payer account and verify ownership
        Account payer = accountRepository
                .findByAccountNumber(request.payerAccountNumber())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Account not found: " + request.payerAccountNumber()));

        if (!payer.getUser().getUsername().equals(username)) {
            throw new SecurityException("You can only generate QR codes for your own accounts.");
        }

        // 2️⃣  Validate PIN upfront
        if (!passwordEncoder.matches(request.pin(), payer.getUser().getPin())) {
            throw new SecurityException("Invalid PIN.");
        }

        // 3️⃣  Balance check upfront — reject if insufficient
        BigDecimal amount = request.amount();
        if (payer.getBalance().compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient balance to generate this QR.");
        }

        // 4️⃣  Build token and expiry
        String qrToken = generateUniqueToken();
        int ttl = (request.ttlMinutes() != null) ? request.ttlMinutes() : DEFAULT_TTL_MIN;
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(ttl);

        // 5️⃣  Persist — payeeAccount is null until someone collects
        //     We reuse the QrPayment entity but store payer in payeeAccount slot
        //     and mark it with a special note prefix "PAYER_QR:" to distinguish.
        //     The real deduction happens at collect time.
        QrPayment qrPayment = QrPayment.builder()
                .qrCode(qrToken)
                .payeeAccount(payer)          // temporarily store payer here
                .amount(amount)
                .currency(payer.getCurrency().name())
                .note("PAYER_QR:" + (request.note() != null ? request.note() : ""))
                .status(QrStatus.PENDING)
                .expiresAt(expiresAt)
                .build();

        qrPaymentRepository.save(qrPayment);
        log.info("💸 Payer QR generated: token={} payer={} amount={}", qrToken,
                request.payerAccountNumber(), amount);

        String base64Image = renderQrToBase64(qrToken);
        return toResponse(qrPayment, base64Image);
    }

    // =============================================================================
    // 4. COLLECT BY QR  (Account B scans and receives money)
    // =============================================================================

    /**
     * Account B scans Account A's payer QR and pulls the pre-authorised amount
     * into their own account. No PIN required from B — A already authorised at generate time.
     *
     * @param request  CollectByQrRequest (qrCode, collectorAccountNumber)
     * @param username authenticated user's username (must own collectorAccountNumber)
     * @return QrPaymentResponse with status COMPLETED
     */
    @Transactional
    public QrPaymentResponse collectByQr(CollectByQrRequest request, String username) {
        // 1️⃣  Acquire pessimistic write lock on the QR entity first to prevent double-spend
        QrPayment qrPayment = qrPaymentRepository.findByQrCodeWithLock(request.qrCode())
                .orElseThrow(() -> new IllegalArgumentException("Invalid QR code."));

        // 2️⃣  Must be a payer-generated QR
        if (qrPayment.getNote() == null || !qrPayment.getNote().startsWith("PAYER_QR:")) {
            throw new IllegalArgumentException(
                    "This QR code is not a Send-by-QR code. Use the pay endpoint instead.");
        }

        // 3️⃣  Status + expiry checks
        if (qrPayment.getStatus() == QrStatus.EXPIRED ||
                LocalDateTime.now().isAfter(qrPayment.getExpiresAt())) {
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

        // Mark as completed immediately under lock
        qrPayment.setStatus(QrStatus.COMPLETED);

        // 4️⃣  Resolve collector account and verify ownership
        Account collector = accountRepository
                .findByAccountNumber(request.collectorAccountNumber())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Collector account not found: " + request.collectorAccountNumber()));

        if (!collector.getUser().getUsername().equals(username)) {
            throw new SecurityException("You can only collect into your own accounts.");
        }

        // 5️⃣  Payer is stored in payeeAccount (see generatePayerQr)
        Account payer = qrPayment.getPayeeAccount();

        // 6️⃣  Cannot collect to same account
        if (payer.getAccountNumber().equals(collector.getAccountNumber())) {
            throw new IllegalArgumentException("You cannot collect a QR into the same account.");
        }

        // 7️⃣  Deterministic row-level locking (min(id) -> max(id)) to guarantee deadlock-free execution
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

        // 8️⃣  Re-check balance on locked entity (may have changed since QR was generated)
        BigDecimal amount = qrPayment.getAmount();
        if (payerLocked.getBalance().compareTo(amount) < 0) {
            throw new IllegalStateException(
                    "Payer's account no longer has sufficient balance.");
        }

        // 9️⃣  Move funds (Debit Payer, Credit Collector Partitioned Bucket)
        payerLocked.setBalance(payerLocked.getBalance().subtract(amount));
        accountRepository.save(payerLocked);

        int bucketIdx = accountBucketService.selectRandomBucketIndex();
        try {
            accountBucketService.creditBucket(collectorLocked.getId(), bucketIdx, amount);
        } catch (Exception e) {
            collectorLocked.setBalance(collectorLocked.getBalance().add(amount));
            accountRepository.save(collectorLocked);
        }

        // 🔟  Record transaction
        String memo = qrPayment.getNote().substring("PAYER_QR:".length());
        Transaction tx = Transaction.builder()
                .fromAccount(payerLocked)
                .toAccount(collectorLocked)
                .amount(amount)
                .transactionType(TransactionType.PAYMENT)
                .status(TransactionStatus.SUCCESS)
                .note("Send-by-QR" + (memo.isEmpty() ? "" : " – " + memo))
                .timestamp(LocalDateTime.now())
                .transactionReference("QR-SEND-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .build();
        transactionRepository.save(tx);

        // 1️⃣1️⃣ Update QR record — swap payerAccount/payeeAccount for the response
        qrPayment.setStatus(QrStatus.COMPLETED);
        qrPayment.setPayerAccount(payerLocked);
        qrPayment.setTransaction(tx);
        qrPayment.setPaidAt(LocalDateTime.now());
        // Store collector in payeeAccount so the response shows correct direction
        qrPayment.setPayeeAccount(collectorLocked);
        qrPaymentRepository.save(qrPayment);

        // 1️⃣2️⃣ Publish Event via Outbox/Kafka for Real-time Notifications
        try {
            eventPublisherService.publishTransactionCompletedEvent(tx);
        } catch (Exception e) {
            log.error("Failed to publish transaction completed event for QR collect TX ID {}: {}", tx.getId(), e.getMessage());
        }

        log.info("✅ Payer QR collected: qrCode={} payer={} collector={} amount={}",
                request.qrCode(), payerLocked.getAccountNumber(),
                collectorLocked.getAccountNumber(), amount);

        return toResponse(qrPayment, null);
    }

    // =============================================================================
    // 3. CANCEL QR
    // =============================================================================

    /**
     * Allows the payee to cancel a pending QR before it is used.
     */
    @Transactional
    public QrPaymentResponse cancelQr(String qrCode, String username) {
        QrPayment qrPayment = qrPaymentRepository.findByQrCode(qrCode)
                .orElseThrow(() -> new IllegalArgumentException("QR code not found."));

        if (!qrPayment.getPayeeAccount().getUser().getUsername().equals(username)) {
            throw new SecurityException("You can only cancel your own QR codes.");
        }
        if (qrPayment.getStatus() != QrStatus.PENDING) {
            throw new IllegalStateException(
                    "Only PENDING QR codes can be cancelled. Current status: " + qrPayment.getStatus());
        }

        qrPayment.setStatus(QrStatus.CANCELLED);
        qrPaymentRepository.save(qrPayment);
        log.info("🚫 QR cancelled: token={}", qrCode);
        return toResponse(qrPayment, null);
    }

    // =============================================================================
    // 4. GET QR HISTORY
    // =============================================================================

    public List<QrPaymentResponse> getQrHistory(String accountNumber, String username) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new IllegalArgumentException("Account not found."));

        if (!account.getUser().getUsername().equals(username)) {
            throw new SecurityException("Access denied.");
        }

        return qrPaymentRepository
                .findByPayeeAccount_AccountNumberOrderByCreatedAtDesc(accountNumber)
                .stream()
                .map(q -> toResponse(q, null))
                .toList();
    }

    // =============================================================================
    // 5. GET OR CREATE ACCOUNT QR  (permanent, no expiry, note = ACCOUNT_QR)
    // =============================================================================

    /**
     * Returns the persistent QR code for an account, creating one if none exists.
     *
     * Unlike regular QRs this one:
     *  - Has no expiry (expiresAt set 100 years in the future)
     *  - Has note = "ACCOUNT_QR"
     *  - Is never expired by the scheduler (status stays PENDING)
     *  - Always re-renders the base64 image so the caller always gets a fresh PNG
     *
     * @param accountNumber  the account number
     * @param username       authenticated user's username (must own the account)
     * @return QrPaymentResponse with a fresh base64 PNG
     */
    @Transactional
    public QrPaymentResponse getOrCreateAccountQr(String accountNumber, String username) {
        // 1️⃣  Resolve account and verify ownership
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Account not found: " + accountNumber));

        if (!account.getUser().getUsername().equals(username)) {
            throw new SecurityException("You can only access QR codes for your own accounts.");
        }

        // 2️⃣  Look for an existing ACCOUNT_QR for this account
        QrPayment qrPayment = qrPaymentRepository
                .findByPayeeAccount_AccountNumberAndNote(accountNumber, "ACCOUNT_QR")
                .orElseGet(() -> {
                    // 3️⃣  None found — create a permanent one
                    String qrToken = generateUniqueToken();
                    QrPayment newQr = QrPayment.builder()
                            .qrCode(qrToken)
                            .payeeAccount(account)
                            .amount(null)               // open-amount
                            .currency(account.getCurrency().name())
                            .note("ACCOUNT_QR")
                            .status(QrStatus.PENDING)
                            .expiresAt(LocalDateTime.now().plusYears(100))   // effectively permanent
                            .build();
                    qrPaymentRepository.save(newQr);
                    log.info("✅ Account QR created: token={} account={}", qrToken, accountNumber);
                    return newQr;
                });

        // 4️⃣  Re-render the image fresh on every call
        String base64Image = renderQrToBase64(qrPayment.getQrCode());
        log.info("📲 Account QR fetched: account={}", accountNumber);
        return toResponse(qrPayment, base64Image);
    }

    // =============================================================================
    // 6. SCHEDULED: EXPIRE STALE QRs
    // =============================================================================

    @Scheduled(fixedDelay = 60_000)   // every 60 seconds
    @Transactional
    public void expireStaleQrCodes() {
        int expired = qrPaymentRepository.expireStaleQrCodes(LocalDateTime.now());
        if (expired > 0) {
            log.info("⏰ Expired {} stale QR codes.", expired);
        }
    }

    // =============================================================================
    // PRIVATE HELPERS
    // =============================================================================

    /**
     * Generates a collision-safe UUID-based token, retrying if a duplicate exists.
     */
    private String generateUniqueToken() {
        String token;
        do {
            token = UUID.randomUUID().toString().replace("-", "");
        } while (qrPaymentRepository.existsByQrCode(token));
        return token;
    }

    /**
     * Renders the QR token string into a 300×300 PNG and returns it as a Base64 string.
     */
    private String renderQrToBase64(String content) {
        try {
            var hints = Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H,
                    EncodeHintType.MARGIN,           2
            );
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE,
                    QR_IMAGE_SIZE, QR_IMAGE_SIZE, hints);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());

        } catch (Exception e) {
            log.error("❌ Failed to render QR image for content={}", content, e);
            throw new RuntimeException("QR image generation failed.", e);
        }
    }

    /**
     * Resolves the actual payment amount:
     *  - Uses QR's fixed amount if set
     *  - Falls back to the amount provided in the PayByQrRequest (open-amount QR)
     */
    private BigDecimal resolveAmount(QrPayment qrPayment, PayByQrRequest request) {
        if (qrPayment.getAmount() != null) {
            return qrPayment.getAmount();   // fixed-amount QR
        }
        if (request.amount() == null) {
            throw new IllegalArgumentException(
                    "This QR code requires you to specify an amount.");
        }
        return request.amount();
    }

    /**
     * Converts a QrPayment entity to the public response DTO.
     */
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
