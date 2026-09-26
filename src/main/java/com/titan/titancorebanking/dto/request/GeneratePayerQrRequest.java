package com.titan.titancorebanking.dto.request;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/**
 * ✅ JAVA 21 Record – Request to generate a "Send by QR" code.
 *
 * The payer (Account A) generates this QR pre-authorising a payment.
 * Account B scans it and calls /api/v1/qr/collect to pull the money.
 *
 * Fields:
 *  - payerAccountNumber : account the money is deducted FROM (required)
 *  - amount             : fixed amount to send (required)
 *  - pin                : payer's PIN — pre-authorises the payment at generate time
 *  - note               : optional memo
 *  - ttlMinutes         : how long the QR is valid (default 15 min)
 */
public record GeneratePayerQrRequest(

    @NotBlank(message = "Payer account number is required")
    @Pattern(regexp = "^[0-9]{10,16}$", message = "Invalid account number format")
    String payerAccountNumber,

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
    @DecimalMax(value = "1000000.00", message = "Amount exceeds maximum allowed")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    BigDecimal amount,

    @NotBlank(message = "PIN is required")
    @Size(min = 4, max = 6, message = "PIN must be 4-6 digits")
    @Pattern(regexp = "^[0-9]+$", message = "PIN must contain only digits")
    String pin,

    @Size(max = 255, message = "Note cannot exceed 255 characters")
    String note,

    @Min(value = 1,   message = "TTL must be at least 1 minute")
    @Max(value = 1440, message = "TTL cannot exceed 24 hours")
    Integer ttlMinutes
) {}
