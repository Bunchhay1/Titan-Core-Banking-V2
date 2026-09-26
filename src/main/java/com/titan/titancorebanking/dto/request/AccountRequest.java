package com.titan.titancorebanking.dto.request;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountRequest {

    @NotBlank(message = "Account type is required")
    @Pattern(regexp = "^(SAVINGS|CHECKING|BUSINESS|INVESTMENT)$", 
             message = "Account type must be SAVINGS, CHECKING, BUSINESS, or INVESTMENT")
    private String accountType;

    @NotBlank(message = "Currency is required")
    @Pattern(regexp = "^(USD|EUR|GBP|JPY|KHR)$", 
             message = "Currency must be USD, EUR, GBP, JPY, or KHR")
    private String currency;

    @DecimalMin(value = "0.00", message = "Initial deposit cannot be negative")
    @DecimalMax(value = "100000.00", message = "Initial deposit exceeds maximum limit")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    private BigDecimal initialDeposit;

    @Size(max = 255, message = "Description cannot exceed 255 characters")
    private String description;
}