package com.titan.notifications.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Mirror of TransactionCompletedEvent published by titan-core-banking.
 *
 * Core-banking publishes (EventPublisherService):
 *   eventId, eventType, transactionId (String), timestamp (Instant),
 *   correlationId, amount, currency, type, status,
 *   sourceAccountNumber, targetAccountNumber, username, note, metadata
 *
 * @JsonIgnoreProperties(ignoreUnknown = true) means extra fields are safely ignored.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransactionCompletedEvent {

    // ── Event metadata ────────────────────────────────────────────────────
    private String eventId;
    private String eventType;
    private String eventVersion;
    private Instant timestamp;
    private String correlationId;

    // ── Transaction data — matches core-banking field names exactly ───────
    private String transactionId;           // String (core-banking sends String.valueOf(tx.getId()))
    private BigDecimal amount;
    private String currency;
    private String type;                    // "TRANSFER" / "DEPOSIT" / "WITHDRAWAL"
    private String status;                  // "SUCCESS" / "FAILED" / "BLOCKED"

    // ── Account info ──────────────────────────────────────────────────────
    private String sourceAccountNumber;     // sender account
    private String targetAccountNumber;     // receiver account
    private String username;                // account owner username

    // ── Optional ─────────────────────────────────────────────────────────
    private String note;
    private String locale;
    private Map<String, String> metadata;

    // ── Convenience helpers for NotificationService ───────────────────────

    /** Returns transactionId as Long for audit logging (null-safe) */
    public Long getTransactionIdAsLong() {
        try {
            return transactionId != null ? Long.parseLong(transactionId) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Returns the primary account identifier.
     * Core-banking doesn't send accountId directly — derive from sourceAccountNumber.
     */
    public String getAccountIdentifier() {
        return sourceAccountNumber != null ? sourceAccountNumber : targetAccountNumber;
    }

    /**
     * Alias for NotificationService which calls event.getTransactionType()
     * Core-banking field is "type" not "transactionType"
     */
    public String getTransactionType() {
        return type;
    }

    /**
     * Alias for NotificationService which calls event.getAccountId()
     * Core-banking doesn't send accountId, return null safely
     */
    public Long getAccountId() {
        return null;
    }
}
