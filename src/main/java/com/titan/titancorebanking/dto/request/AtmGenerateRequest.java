package com.titan.titancorebanking.dto.request;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/**
 * Request body for POST /api/v1/atm/generate
 *
 * The authenticated customer asks to generate a one-time 12-digit
 * ATM code to withdraw a specific amount from one of their accounts.
 */
public record AtmGenerateRequest(

    @NotBlank(message = "Account number is required")
    @Pattern(regexp = "^[0-9]{10,16}$", message = "Invalid account number format")
    String accountNumber,

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "1.00", message = "Minimum withdrawal is 1.00")
    @DecimalMax(value = "10000.00", message = "Maximum ATM withdrawal is 10,000.00")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    BigDecimal amount,

    /** Customer PIN – required to authorise the code generation. */
    @NotBlank(message = "PIN is required")
    @Size(min = 4, max = 6, message = "PIN must be 4-6 digits")
    @Pattern(regexp = "^[0-9]+$", message = "PIN must contain only digits")
    String pin
) {}
