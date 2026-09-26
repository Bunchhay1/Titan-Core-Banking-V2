package com.titan.notifications.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

/**
 * HTTP payload sent by titan-core-banking to POST /api/notify/transaction.
 * Replaces Kafka for the direct REST integration.
 *
 * Core-banking calls this endpoint synchronously (fire-and-forget with timeout)
 * after every successful transfer, deposit, or withdrawal.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransactionNotificationRequest {

    // ── Transaction identity ──────────────────────────────────────────────────
    private String transactionId;
    private String type;          // TRANSFER | DEPOSIT | WITHDRAWAL
    private String status;        // SUCCESS | BLOCKED | FAILED

    // ── Money ─────────────────────────────────────────────────────────────────
    private BigDecimal amount;
    private String currency;

    // ── Accounts ──────────────────────────────────────────────────────────────
    private String sourceAccountNumber;
    private String targetAccountNumber;

    // ── User ─────────────────────────────────────────────────────────────────
    private String username;
    private String userEmail;     // injected by core-banking from User entity
    private String locale;        // "en" | "km"

    // ── Optional ─────────────────────────────────────────────────────────────
    private String note;
    private Map<String, String> metadata;
}
