package com.titan.titancorebanking.dto.request;

import jakarta.validation.constraints.*;

/**
 * ✅ JAVA 21 Record – Request to collect money from a payer-generated QR.
 *
 * Account B scans Account A's "Send by QR" code and submits this request.
 * Money flows FROM the payer (Account A) TO the collector (Account B).
 *
 * Fields:
 *  - qrCode                  : unique token decoded from the QR image
 *  - collectorAccountNumber  : account that will RECEIVE the money
 */
public record CollectByQrRequest(

    @NotBlank(message = "QR code token is required")
    String qrCode,

    @NotBlank(message = "Collector account number is required")
    @Pattern(regexp = "^[0-9]{10,16}$", message = "Invalid account number format")
    String collectorAccountNumber
) {}
