package com.titan.titancorebanking.event.kafka;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Kafka Event: Transaction Completed
 *
 * Published to: banking.transactions.completed
 *
 * Schema is the authoritative contract between titan-core-banking (producer)
 * and titan-notifications-service (consumer).  Do NOT add fields here without
 * updating TransactionCompletedEvent in the notifications service as well.
 *
 * Field naming follows the notifications service mirror class:
 *   - type              → TRANSFER | TRANSFER_RECEIVED | DEPOSIT | WITHDRAWAL
 *   - sourceAccountNumber / targetAccountNumber  (not toAccountNumber)
 *   - metadata          → Map<String,String> for senderName, receiverName, etc.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionCompletedEvent {

    // ── Event metadata ────────────────────────────────────────────────────
    private String eventId;

    @Builder.Default
    private String eventType = "TransactionCompleted";

    @Builder.Default
    private String eventVersion = "1.0";

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant timestamp;

    private String correlationId;

    // ── Transaction data ──────────────────────────────────────────────────
    /** String form of the DB transaction ID (e.g. "28" or "28-recv" for receiver side) */
    private String transactionId;

    private BigDecimal amount;
    private String currency;

    /**
     * Transaction type identifier.
     * Values: TRANSFER, TRANSFER_RECEIVED, DEPOSIT, WITHDRAWAL
     * (TRANSFER_RECEIVED = Account B / receiver perspective)
     */
    private String type;

    private String status;  // SUCCESS, FAILED, BLOCKED

    // ── Account info ──────────────────────────────────────────────────────
    private String sourceAccountNumber;   // sender's account number
    private String targetAccountNumber;   // receiver's account number

    /** Username of the account owner this event is targeted at */
    private String username;

    // ── Optional ─────────────────────────────────────────────────────────
    private String note;

    /**
     * Rich metadata injected by EventPublisherService.
     * Keys: senderName, receiverName, senderAccount, receiverAccount, userEmail, source, channel
     */
    private Map<String, String> metadata;
}
