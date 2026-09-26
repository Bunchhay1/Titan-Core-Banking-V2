package com.titan.titancorebanking.dto.request;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/**
 * ✅ JAVA 21 Record – Request to pay using a scanned QR code.
 *
 * Fields:
 *  - qrCode             : unique token decoded from the QR image (required)
 *  - payerAccountNumber : account the money is deducted from (required)
 *  - amount             : required only when QR was generated without a fixed amount
 *  - pin                : payer's account PIN for authorization
 */
public record PayByQrRequest(

    @NotBlank(message = "QR code token is required")
    String qrCode,

    @NotBlank(message = "Payer account number is required")
    @Pattern(regexp = "^[0-9]{10,16}$", message = "Invalid account number format")
    String payerAccountNumber,

    @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
    @DecimalMax(value = "1000000.00", message = "Amount exceeds maximum allowed")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    BigDecimal amount,          // required only for open-amount QRs

    @NotBlank(message = "PIN is required")
    @Size(min = 4, max = 6, message = "PIN must be 4-6 digits")
    @Pattern(regexp = "^[0-9]+$", message = "PIN must contain only digits")
    String pin
) {}
