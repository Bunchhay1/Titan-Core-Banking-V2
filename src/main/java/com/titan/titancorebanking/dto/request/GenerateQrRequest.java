package com.titan.titancorebanking.dto.request;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/**
 * ✅ JAVA 21 Record – Request to generate a QR code for receiving payment.
 *
 * Fields:
 *  - payeeAccountNumber : account that will receive funds (required)
 *  - amount             : optional fixed amount (null = open / payer enters amount)
 *  - note               : optional memo shown to payer
 *  - ttlMinutes         : how long the QR is valid (default 15 in service)
 */
public record GenerateQrRequest(

    @NotBlank(message = "Payee account number is required")
    @Pattern(regexp = "^[0-9]{10,16}$", message = "Invalid account number format")
    String payeeAccountNumber,

    @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
    @DecimalMax(value = "1000000.00", message = "Amount exceeds maximum allowed")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    BigDecimal amount,          // nullable → open-amount QR

    @Size(max = 255, message = "Note cannot exceed 255 characters")
    String note,

    @Min(value = 1,  message = "TTL must be at least 1 minute")
    @Max(value = 1440, message = "TTL cannot exceed 1440 minutes (24 hours)")
    Integer ttlMinutes          // nullable → defaults to 15
) {}
