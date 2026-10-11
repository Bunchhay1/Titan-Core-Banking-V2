package com.titan.titancorebanking.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Immutable QR Payment Response.
 * Includes the base64-encoded image string for immediate frontend rendering[cite: 1].
 */
public record QrPaymentResponse(
        Long id,
        String qrCode,
        String qrImageBase64,
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