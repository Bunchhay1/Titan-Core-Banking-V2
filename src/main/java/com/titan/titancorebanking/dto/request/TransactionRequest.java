package com.titan.titancorebanking.dto.request;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/**
 * ✅ JAVA 21: Immutable Record (No Lombok needed)
 * Automatically generates: constructor, getters, equals, hashCode, toString
 */
public record TransactionRequest(
    @Pattern(regexp = "^[0-9]{10,16}$", message = "Invalid account number format")
    String fromAccountNumber,

    @Pattern(regexp = "^[0-9]{10,16}$", message = "Invalid account number format")
    String toAccountNumber,

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
    @DecimalMax(value = "1000000.00", message = "Amount exceeds maximum limit")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    BigDecimal amount,

    @Size(min = 4, max = 6, message = "PIN must be 4-6 digits")
    @Pattern(regexp = "^[0-9]+$", message = "PIN must contain only digits")
    String pin,

    @Size(max = 255, message = "Note cannot exceed 255 characters")
    String note,

    String otpCode,
    String transactionType,
    String idempotencyKey,
    String swiftCode,
    String iban
) {}