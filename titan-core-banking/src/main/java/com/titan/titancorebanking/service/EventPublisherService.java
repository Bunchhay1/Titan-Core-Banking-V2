package com.titan.titancorebanking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.titan.titancorebanking.event.kafka.TransactionCompletedEvent;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.OutboxEvent;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.repository.OutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class EventPublisherService {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final com.titan.titancorebanking.service.imple.ExchangeRateService exchangeRateService;

    /**
     * Whether Kafka is enabled. When false, notification events are sent directly
     * via HTTP to the notification service instead of through the Outbox/Kafka path.
     */
    @Value("${kafka.enabled:false}")
    private boolean kafkaEnabled;

    /**
     * URL of titan-notifications-service.
     * Used only when kafkaEnabled=false (local dev / Render free tier).
     * Defaults to localhost:8084 for local development.
     */
    @Value("${notification.service.url:http://localhost:8084}")
    private String notificationServiceUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public EventPublisherService(OutboxRepository outboxRepository,
                                 ObjectMapper objectMapper,
                                 com.titan.titancorebanking.service.imple.ExchangeRateService exchangeRateService) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.exchangeRateService = exchangeRateService;
    }

    /**
     * 🚀 Publish Transaction Event via Outbox Pattern
     * Guarantees atomicity: DB commit = Event guaranteed delivery
     * For TRANSFER: saves TWO outbox events — sender (Account A) + receiver (Account B)
     *
     * When kafka.enabled=false (local dev / Render free tier without Confluent Cloud):
     * The outbox is bypassed and events are pushed directly over HTTP asynchronously.
     */
    public void publishTransactionCompletedEvent(Transaction transaction) {
        try {
            // Event 1: Sender / Primary transaction owner (Account A)
            TransactionCompletedEvent event = buildTransactionCompletedEvent(transaction);
            
            // Event 2: Receiver (Account B) — only for TRANSFER transactions where toAccount exists
            TransactionCompletedEvent receiverEvent = null;
            if (transaction.getTransactionType() == com.titan.titancorebanking.enums.TransactionType.TRANSFER
                    && transaction.getToAccount() != null
                    && transaction.getFromAccount() != null) {
                receiverEvent = buildReceiverEvent(transaction);
            }

            // ── Save to DB outbox table ───────────────────────────────────────────
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateType("TRANSACTION")
                    .aggregateId(transaction.getId().toString())
                    .eventType("TransactionCompleted")
                    .payload(objectMapper.writeValueAsString(event))
                    .build();

            outboxRepository.save(outboxEvent);
            log.info("✅ Saved outbox event for TX ID: {} (Sender)", transaction.getId());

            if (receiverEvent != null) {
                OutboxEvent receiverOutboxEvent = OutboxEvent.builder()
                        .aggregateType("TRANSACTION")
                        .aggregateId(transaction.getId().toString() + "-recv")
                        .eventType("TransactionCompleted")
                        .payload(objectMapper.writeValueAsString(receiverEvent))
                        .build();

                outboxRepository.save(receiverOutboxEvent);
                log.info("✅ Saved outbox event for TX ID: {} (Receiver)", transaction.getId());
            }

            // ── HTTP fallback — fire when Kafka/Outbox relay is not running ──────
            // When kafka.enabled=false the OutboxRelayService bean is inactive, so
            // the outbox records will never be forwarded to Kafka. We compensate by
            // calling the notification service directly via HTTP (fire-and-forget).
            if (!kafkaEnabled) {
                final TransactionCompletedEvent senderEventFinal  = event;
                final TransactionCompletedEvent receiverEventFinal = receiverEvent;

                Thread.ofVirtual().name("notify-http-", 0).start(() -> {
                    sendHttpNotification(senderEventFinal);
                    if (receiverEventFinal != null) {
                        sendHttpNotification(receiverEventFinal);
                    }
                });
            }

        } catch (Exception e) {
            log.error("❌ Failed to save event to outbox: TX ID: {}", transaction.getId(), e);
            throw new RuntimeException("Failed to save event", e);
        }
    }

    /**
     * Build a TRANSFER_RECEIVED event for Account B (the receiver).
     * username = receiver's username so the notification service
     * writes the audit record under the correct user.
     */
    private TransactionCompletedEvent buildReceiverEvent(Transaction tx) {
        Account receiver = tx.getToAccount();
        Account sender   = tx.getFromAccount();

        // ── TRIM: remove any accidental whitespace from usernames ─────────────
        String receiverUsername = (receiver.getUser() != null && receiver.getUser().getUsername() != null)
                ? receiver.getUser().getUsername().trim() : receiver.getAccountNumber().trim();
        String senderUsername   = (sender.getUser() != null && sender.getUser().getUsername() != null)
                ? sender.getUser().getUsername().trim() : sender.getAccountNumber().trim();
        String receiverEmail    = (receiver.getUser() != null) ? receiver.getUser().getEmail() : null;

        String currency      = receiver.getCurrency().name();
        String correlationId = MDC.get("correlationId") != null
                ? MDC.get("correlationId") : UUID.randomUUID().toString();

        // Calculate amount in receiver's currency if cross-currency transfer
        java.math.BigDecimal receiverAmount = tx.getAmount();
        if (sender != null && sender.getCurrency() != receiver.getCurrency()) {
            receiverAmount = exchangeRateService.convert(tx.getAmount(), sender.getCurrency(), receiver.getCurrency());
        }

        // ── Rich metadata — notification service uses these to build the alert ─
        Map<String, String> metadata = new HashMap<>();
        metadata.put("source",              "titan-core-banking");
        metadata.put("channel",             "mobile-app");
        metadata.put("senderName",          senderUsername);          // "navatra"
        metadata.put("receiverName",        receiverUsername);        // "vanda"
        metadata.put("senderAccount",       sender.getAccountNumber());
        metadata.put("receiverAccount",     receiver.getAccountNumber());
        // ── accountId: receiver (Account B) owns this event ──────────────
        metadata.put("accountId",           String.valueOf(receiver.getId()));
        if (receiverEmail != null && !receiverEmail.isBlank()) {
            metadata.put("userEmail", receiverEmail);
        }

        return TransactionCompletedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("TransactionCompleted")
                .transactionId(tx.getId().toString() + "-recv")
                .timestamp(Instant.now())
                .correlationId(correlationId)
                .amount(receiverAmount)
                .currency(currency)
                .type("TRANSFER_RECEIVED")          // ← tells notification service: this is Account B
                .status(tx.getStatus().name())
                .sourceAccountNumber(sender.getAccountNumber())
                .targetAccountNumber(receiver.getAccountNumber())
                .username(receiverUsername)          // ← Account B's trimmed username
                .note(tx.getNote())
                .metadata(metadata)
                .build();
    }

    private TransactionCompletedEvent buildTransactionCompletedEvent(Transaction tx) {
        // ── For DEPOSIT, fromAccount is null — the owning account is toAccount. ──
        // For TRANSFER / WITHDRAWAL, fromAccount is the initiating account.
        boolean isDeposit = tx.getFromAccount() == null && tx.getToAccount() != null;

        // Account that owns this event (= the user who should receive the notification)
        Account ownerAccount   = isDeposit ? tx.getToAccount() : tx.getFromAccount();
        Account receiverAccount = tx.getToAccount();

        // ── TRIM: remove any accidental whitespace from usernames ─────────────
        String ownerUsername = (ownerAccount != null && ownerAccount.getUser() != null
                && ownerAccount.getUser().getUsername() != null)
                ? ownerAccount.getUser().getUsername().trim()
                : (ownerAccount != null ? ownerAccount.getAccountNumber().trim() : "SYSTEM");

        String receiverUsername = (receiverAccount != null && receiverAccount.getUser() != null
                && receiverAccount.getUser().getUsername() != null)
                ? receiverAccount.getUser().getUsername().trim()
                : null;

        String currency = (ownerAccount != null)
                ? ownerAccount.getCurrency().name()
                : "USD";

        String correlationId = MDC.get("correlationId") != null
                ? MDC.get("correlationId") : UUID.randomUUID().toString();

        // ── Rich metadata — notification service uses these to build the alert ─
        Map<String, String> metadata = new HashMap<>();
        metadata.put("source",          "titan-core-banking");
        metadata.put("channel",         "mobile-app");

        if (isDeposit) {
            // DEPOSIT: ownerAccount is the deposit target (toAccount)
            metadata.put("receiverName",    ownerUsername);
            metadata.put("receiverAccount", ownerAccount.getAccountNumber());
            metadata.put("accountId",       String.valueOf(ownerAccount.getId()));
            if (ownerAccount.getUser() != null) {
                String email = ownerAccount.getUser().getEmail();
                if (email != null && !email.isBlank()) {
                    metadata.put("userEmail", email);
                }
            }
        } else {
            // TRANSFER / WITHDRAWAL: ownerAccount is fromAccount (the sender)
            metadata.put("senderName",      ownerUsername);
            metadata.put("senderAccount",   ownerAccount != null ? ownerAccount.getAccountNumber() : "");
            if (receiverUsername != null) {
                metadata.put("receiverName",    receiverUsername);
            }
            if (receiverAccount != null) {
                metadata.put("receiverAccount", receiverAccount.getAccountNumber());
            }
            if (ownerAccount != null) {
                metadata.put("accountId", String.valueOf(ownerAccount.getId()));
                if (ownerAccount.getUser() != null) {
                    String email = ownerAccount.getUser().getEmail();
                    if (email != null && !email.isBlank()) {
                        metadata.put("userEmail", email);
                    }
                }
            }
        }

        return TransactionCompletedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("TransactionCompleted")
                .transactionId(String.valueOf(tx.getId()))
                .timestamp(Instant.now())
                .correlationId(correlationId)
                .amount(tx.getAmount())
                .currency(currency)
                .type(tx.getTransactionType().name())
                .status(tx.getStatus().name())
                // sourceAccountNumber = sender (null for DEPOSIT — that's correct)
                .sourceAccountNumber(tx.getFromAccount() != null ? tx.getFromAccount().getAccountNumber() : null)
                // targetAccountNumber = destination account (always the deposit/receiver account)
                .targetAccountNumber(tx.getToAccount() != null ? tx.getToAccount().getAccountNumber() : null)
                // username = the account owner who should receive this notification
                .username(ownerUsername)
                .note(tx.getNote())
                .metadata(metadata)
                .build();
    }

    // =========================================================================
    // HTTP FALLBACK — used when kafka.enabled=false
    // =========================================================================

    /**
     * Fire-and-forget HTTP POST to titan-notifications-service.
     *
     * Called from a virtual thread so it never blocks the transaction commit.
     * Failures are logged as warnings — a notification failure must never
     * roll back the financial transaction.
     */
    private void sendHttpNotification(TransactionCompletedEvent event) {
        try {
            String url = notificationServiceUrl + "/api/notify/transaction";
            String json = objectMapper.writeValueAsString(event);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                log.info("📢 [HTTP] Notification accepted by notification service: txId={} type={}",
                        event.getTransactionId(), event.getType());
            } else {
                log.warn("⚠️ [HTTP] Notification service returned {}: txId={} body={}",
                        response.statusCode(), event.getTransactionId(), response.body());
            }
        } catch (Exception e) {
            // Log and swallow — notification failure must NOT affect the transaction
            log.warn("⚠️ [HTTP] Failed to notify for txId={}: {}", event.getTransactionId(), e.getMessage());
        }
    }
}