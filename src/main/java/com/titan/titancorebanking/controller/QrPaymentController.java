package com.titan.titancorebanking.controller;

import com.titan.titancorebanking.dto.request.GenerateQrRequest;
import com.titan.titancorebanking.dto.request.GeneratePayerQrRequest;
import com.titan.titancorebanking.dto.request.CollectByQrRequest;
import com.titan.titancorebanking.dto.request.PayByQrRequest;
import com.titan.titancorebanking.dto.response.QrPaymentResponse;
import com.titan.titancorebanking.service.QrPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * ✅ TITAN QR Payment Controller
 *
 * Base path: /api/v1/qr
 *
 * Endpoints:
 *  POST   /api/v1/qr/generate                  – generate QR code for receiving payment
 *  POST   /api/v1/qr/pay                        – pay by scanning a QR code
 *  POST   /api/v1/qr/cancel/{qrCode}            – cancel a pending QR code
 *  GET    /api/v1/qr/history/{accountNumber}    – list all QR payments for an account
 */
@RestController
@RequestMapping("/api/v1/qr")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "QR Payment API", description = "Generate QR codes for payment and pay via QR code scanning")
public class QrPaymentController {

    private final QrPaymentService qrPaymentService;

    // ─────────────────────────────────────────────────────────────────────────────
    // 1. GENERATE QR
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Generates a QR code that others can scan to send money to your account.
     *
     * Example body:
     * {
     *   "payeeAccountNumber": "1234567890",
     *   "amount": 50.00,          // optional – omit for open-amount QR
     *   "note": "Lunch split",
     *   "ttlMinutes": 15          // optional – defaults to 15
     * }
     *
     * Response includes a base64 PNG string. Display it as:
     * <img src="data:image/png;base64,{qrImageBase64}" />
     */
    @PostMapping("/generate")
    @Operation(
        summary     = "Generate QR Code",
        description = "Creates a QR code for the payee account. " +
                      "Optionally set a fixed amount; leave blank for open-amount QR."
    )
    public ResponseEntity<QrPaymentResponse> generateQr(
            @Valid @RequestBody GenerateQrRequest request,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        log.info("📲 Generate QR request: account={} amount={}",
                request.payeeAccountNumber(), request.amount());
        QrPaymentResponse response = qrPaymentService.generateQr(request, userDetails.getUsername());
        return ResponseEntity.ok(response);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 2. PAY BY QR
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Processes a payment initiated by scanning a QR code.
     *
     * Example body:
     * {
     *   "qrCode": "abc123token...",
     *   "payerAccountNumber": "9876543210",
     *   "amount": 50.00,   // required only for open-amount QRs
     *   "pin": "1234"
     * }
     */
    @PostMapping("/pay")
    @Operation(
        summary     = "Pay via QR Code",
        description = "Deducts the amount from the payer's account and credits the payee. " +
                      "PIN verification is required."
    )
    public ResponseEntity<QrPaymentResponse> payByQr(
            @Valid @RequestBody PayByQrRequest request,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        log.info("💳 QR Pay request: qrCode={} payer={}", request.qrCode(), request.payerAccountNumber());
        QrPaymentResponse response = qrPaymentService.payByQr(request, userDetails.getUsername());
        return ResponseEntity.ok(response);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 3. GENERATE PAYER QR  (Send by QR)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Account A generates a "Send by QR" code pre-authorised with their PIN.
     * Account B scans it and calls /collect to receive the money.
     *
     * Example body:
     * {
     *   "payerAccountNumber": "1234567890",
     *   "amount": 500.00,
     *   "pin": "1234",
     *   "note": "For you",
     *   "ttlMinutes": 15
     * }
     */
    @PostMapping("/generate-payer")
    @Operation(
        summary     = "Generate Send-by-QR Code",
        description = "Payer pre-authorises a payment. " +
                      "Anyone who scans this QR and calls /collect receives the money."
    )
    public ResponseEntity<QrPaymentResponse> generatePayerQr(
            @Valid @RequestBody GeneratePayerQrRequest request,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        log.info("💸 Generate Payer QR: account={} amount={}",
                request.payerAccountNumber(), request.amount());
        QrPaymentResponse response = qrPaymentService.generatePayerQr(request, userDetails.getUsername());
        return ResponseEntity.ok(response);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 4. COLLECT BY QR
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Account B scans Account A's Send-by-QR code and collects the money.
     *
     * Example body:
     * {
     *   "qrCode": "abc123token...",
     *   "collectorAccountNumber": "9876543210"
     * }
     */
    @PostMapping("/collect")
    @Operation(
        summary     = "Collect money from a Send-by-QR code",
        description = "Collector scans the payer's QR code and receives the pre-authorised amount."
    )
    public ResponseEntity<QrPaymentResponse> collectByQr(
            @Valid @RequestBody CollectByQrRequest request,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        log.info("📥 Collect QR: qrCode={} collector={}", request.qrCode(), request.collectorAccountNumber());
        QrPaymentResponse response = qrPaymentService.collectByQr(request, userDetails.getUsername());
        return ResponseEntity.ok(response);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 3. CANCEL QR
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Cancels a PENDING QR code. Only the payee (account owner) can cancel.
     */
    @PostMapping("/cancel/{qrCode}")
    @Operation(
        summary     = "Cancel QR Code",
        description = "Cancels a pending QR code so it can no longer be used for payment."
    )
    public ResponseEntity<QrPaymentResponse> cancelQr(
            @PathVariable String qrCode,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        log.info("🚫 Cancel QR request: qrCode={}", qrCode);
        QrPaymentResponse response = qrPaymentService.cancelQr(qrCode, userDetails.getUsername());
        return ResponseEntity.ok(response);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 4. QR HISTORY
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Returns all QR payments (generated) for a specific account, newest first.
     */
    @GetMapping("/history/{accountNumber}")
    @Operation(
        summary     = "QR Payment History",
        description = "Returns all QR codes generated for the given account, ordered newest first."
    )
    public ResponseEntity<List<QrPaymentResponse>> getQrHistory(
            @PathVariable String accountNumber,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        log.info("📜 QR history request: account={}", accountNumber);
        List<QrPaymentResponse> history = qrPaymentService.getQrHistory(accountNumber, userDetails.getUsername());
        return ResponseEntity.ok(history);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 5. GET / CREATE ACCOUNT QR  (permanent, no expiry)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Returns the persistent account QR code for the given account number.
     * Auto-creates a permanent QR (note = "ACCOUNT_QR", no expiry) if none exists yet.
     *
     * Only the account owner may call this endpoint.
     *
     * Response includes a freshly rendered base64 PNG on every call.
     */
    @GetMapping("/account/{accountNumber}")
    @Operation(
        summary     = "Get or Create Account QR",
        description = "Returns the permanent QR code for this account. " +
                      "Auto-creates one if none exists. Only the account owner may call this."
    )
    public ResponseEntity<QrPaymentResponse> getOrCreateAccountQr(
            @PathVariable String accountNumber,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        log.info("📱 Account QR request: account={}", accountNumber);
        QrPaymentResponse response = qrPaymentService.getOrCreateAccountQr(
                accountNumber, userDetails.getUsername());
        return ResponseEntity.ok(response);
    }
}
