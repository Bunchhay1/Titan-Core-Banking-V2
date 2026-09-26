package com.titan.titancorebanking.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ✅ JAVA 21 Record – Response after generating or paying via QR.
 *
 * qrImageBase64 : Base64-encoded PNG of the QR code image (non-null on generate, null on pay)
 * qrCode        : raw token string embedded in the QR
 * status        : PENDING | COMPLETED | EXPIRED | CANCELLED
 * amount        : fixed amount or null for open-amount QRs
 * payeeAccount  : account that will/did receive money
 * payerAccount  : account that paid (null until payment)
 * note          : memo
 * expiresAt     : when the QR expires
 * paidAt        : when the QR was paid (null until payment)
 * transactionId : settled transaction ID (null until payment)
 */
public record QrPaymentResponse(
    Long id,
    String qrCode,
    String qrImageBase64,   // PNG as base64 – display with <img src="data:image/png;base64,...">
    String status,
    BigDecimal amount,
    String currency,
    String payeeAccount,
    String payerAccount,
    String note,
    LocalDateTime createdAt,
    LocalDateTime expiresAt,
    LocalDateTime paidAt,
    Long transactionId
) {}
