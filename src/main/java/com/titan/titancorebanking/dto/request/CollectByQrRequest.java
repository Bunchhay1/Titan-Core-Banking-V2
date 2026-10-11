package com.titan.titancorebanking.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CollectByQrRequest(
        @NotBlank(message = "QR code token is required")
        String qrCode,

        @NotBlank(message = "Collector account number is required")
        String collectorAccountNumber
) {}