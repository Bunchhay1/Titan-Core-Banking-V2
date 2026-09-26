package com.titan.promotions.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Transaction Completed Event
 * Mirror of the event published by titan-core-banking → OutboxRelayService → Kafka.
 *
 * Core-banking serializes transactionId as a String (e.g. "28" or "28-recv"),
 * not a Long, so we keep it as String here.
 * accountId is NOT a top-level field in the core-banking event; it is resolved
 * from the metadata map by PromotionEvaluationService.
 *
 * Field mapping from core-banking TransactionCompletedEvent:
 *   type                → mapped to transactionType (+ kept as "type" alias via @JsonAlias)
 *   sourceAccountNumber → sender account
 *   targetAccountNumber → receiver account
 *   username            → the account owner this event targets
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
    private String timestamp;
    private String correlationId;

    // ── Transaction core fields ───────────────────────────────────────────
    /**
     * String form of the DB transaction ID (e.g. "28" or "28-recv").
     * Core-banking publishes this as String — do NOT change to Long.
     */
    private String transactionId;

    private BigDecimal amount;
    private String currency;

    /**
     * TRANSFER | TRANSFER_RECEIVED | DEPOSIT | WITHDRAWAL
     * Core-banking sends this field as "type"; Jackson maps it here
     * because we alias it in the SpEL context as #transactionType.
     */
    private String type;           // raw field name from core-banking

    private String status;

    // ── Account info ──────────────────────────────────────────────────────
    private String sourceAccountNumber;   // sender account number
    private String targetAccountNumber;   // receiver account number

    /**
     * Username of the account owner this event targets.
     * For DEPOSIT/WITHDRAWAL: depositor's username.
     * For TRANSFER: sender's username.
     * For TRANSFER_RECEIVED: receiver's username.
     */
    private String username;

    private String note;

    /**
     * Rich metadata from core-banking.
     * Keys include: senderName, receiverName, senderAccount, receiverAccount,
     *               userEmail, source, channel, accountId (added by Task 2).
     */
    private Map<String, String> metadata;

    // ── Derived helper ────────────────────────────────────────────────────
    /**
     * Returns the transaction type suitable for SpEL evaluation.
     * The core-banking field is "type"; this method normalises it so
     * RuleEngine can bind it as #transactionType.
     */
    public String getTransactionType() {
        return type;
    }

    /**
     * Resolve accountId from the metadata map (injected by core-banking
     * EventPublisherService after Task 2 fix).
     * Returns null if metadata is absent or key not set.
     */
    public Long getAccountId() {
        if (metadata == null) return null;
        String val = metadata.get("accountId");
        if (val == null || val.isBlank()) return null;
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
