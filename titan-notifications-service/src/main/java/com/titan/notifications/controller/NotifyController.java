package com.titan.notifications.controller;

import com.titan.notifications.dto.TransactionNotificationRequest;
import com.titan.notifications.event.TransactionCompletedEvent;
import com.titan.notifications.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * HTTP entry point for direct REST calls from titan-core-banking.
 *
 * When Kafka is NOT available (e.g., Render free tier without Confluent Cloud),
 * core-banking calls POST /api/notify/transaction after every transaction.
 *
 * This controller converts the HTTP payload into the same TransactionCompletedEvent
 * that the Kafka consumer uses — so NotificationService is called identically
 * regardless of the transport (Kafka or HTTP).
 */
@RestController
@RequestMapping("/api/notify")
@Slf4j
@RequiredArgsConstructor
public class NotifyController {

    private final NotificationService notificationService;

    /**
     * POST /api/notify/transaction
     *
     * Called by titan-core-banking after every successful transaction.
     * Core-banking fires this as fire-and-forget (3 s timeout) so it never
     * blocks the transaction itself even if the notification service is slow.
     *
     * Request body: TransactionNotificationRequest JSON
     * Response:     200 OK immediately (processing happens async)
     *
     * TRANSFER handling — two notifications fired per transfer:
     *   1. Sender event  (type=TRANSFER,          username=senderUsername)
     *   2. Receiver event (type=TRANSFER_RECEIVED, username=receiverUsername)
     *
     * The receiver's username is read from metadata.receiverName (populated by
     * titan-core-banking's EventPublisherService). If receiverName is absent in
     * metadata the receiver notification is skipped gracefully.
     */
    @PostMapping("/transaction")
    public ResponseEntity<Map<String, String>> notifyTransaction(
            @RequestBody TransactionNotificationRequest req) {

        log.info("📨 HTTP notify received: txId={}, type={}, amount={}, user={}",
                req.getTransactionId(), req.getType(), req.getAmount(), req.getUsername());

        // Convert HTTP payload → TransactionCompletedEvent (same model Kafka uses)
        TransactionCompletedEvent senderEvent = toEvent(req);

        // Process async so we respond instantly and don't block core-banking
        Thread.ofVirtual().name("notify-", 0).start(() -> {
            try {
                // ── 1. Sender notification ─────────────────────────────────────
                notificationService.sendNotifications(senderEvent);

                // ── 2. Receiver notification (TRANSFER only) ───────────────────
                // When Kafka is disabled core-banking only calls this endpoint once
                // (for the sender). We must fire the TRANSFER_RECEIVED event here
                // so the receiver account also gets an in-app + push notification.
                if ("TRANSFER".equalsIgnoreCase(req.getType())) {
                    TransactionCompletedEvent receiverEvent = buildReceiverEvent(req);
                    if (receiverEvent != null) {
                        log.info("📨 Firing TRANSFER_RECEIVED for receiver: txId={}, receiver={}",
                                req.getTransactionId(), receiverEvent.getUsername());
                        notificationService.sendNotifications(receiverEvent);
                    }
                }
            } catch (Exception e) {
                log.error("❌ Notification processing error: {}", e.getMessage(), e);
            }
        });

        return ResponseEntity.ok(Map.of(
                "status", "accepted",
                "transactionId", req.getTransactionId() != null ? req.getTransactionId() : "unknown"
        ));
    }

    /**
     * GET /api/notify/health
     * Simple ping so core-banking can verify connectivity on startup.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "titan-notifications"));
    }

    // ── Convert HTTP DTO → internal event model ───────────────────────────────
    private TransactionCompletedEvent toEvent(TransactionNotificationRequest req) {
        // Normalise type: TRANSFER_RECEIVED is the receiver-side leg of a TRANSFER
        // Keep it as-is — NotificationService will use it to craft the right message.
        String type = req.getType() != null ? req.getType() : "TRANSFER";

        return TransactionCompletedEvent.builder()
                .eventId(java.util.UUID.randomUUID().toString())
                .eventType("TransactionCompleted")
                .eventVersion("1.0")
                .transactionId(req.getTransactionId())
                .timestamp(java.time.Instant.now())
                .amount(req.getAmount())
                .currency(req.getCurrency())
                .type(type)
                .status(req.getStatus())
                .sourceAccountNumber(req.getSourceAccountNumber())
                .targetAccountNumber(req.getTargetAccountNumber())
                .username(req.getUsername())
                .note(req.getNote())
                .locale(req.getLocale())
                // Inject userEmail into metadata so NotificationService.resolveEmail() picks it up
                .metadata(buildMetadata(req))
                .build();
    }

    /**
     * Build the receiver-side (TRANSFER_RECEIVED) event from the sender's request.
     *
     * Receiver info is sourced from:
     *   - metadata.receiverName    → receiver's username   (set by core-banking EventPublisherService)
     *   - metadata.receiverAccount → receiver's account number
     *   - targetAccountNumber      → fallback if metadata keys are absent
     *
     * Returns null if receiver username cannot be determined (skips notification gracefully).
     */
    private TransactionCompletedEvent buildReceiverEvent(TransactionNotificationRequest req) {
        java.util.Map<String, String> meta = req.getMetadata() != null
                ? new java.util.HashMap<>(req.getMetadata()) : new java.util.HashMap<>();

        // Resolve receiver username — prefer metadata.receiverName
        String receiverUsername = meta.get("receiverName");
        if (receiverUsername == null || receiverUsername.isBlank()) {
            // Fall back to targetAccountNumber if no receiverName in metadata
            receiverUsername = req.getTargetAccountNumber();
        }
        if (receiverUsername == null || receiverUsername.isBlank()) {
            log.warn("⚠️ Cannot build TRANSFER_RECEIVED event — no receiver info in metadata for txId={}",
                    req.getTransactionId());
            return null;
        }

        // Ensure senderName is in metadata so the notification body reads
        // "You received $X from <senderName>"
        if (!meta.containsKey("senderName") && req.getUsername() != null) {
            meta.put("senderName", req.getUsername());
        }
        if (!meta.containsKey("senderAccount") && req.getSourceAccountNumber() != null) {
            meta.put("senderAccount", req.getSourceAccountNumber());
        }
        meta.put("transport", "http");
        meta.put("source", "titan-core-banking");

        // Receiver-side txId gets the "-recv" suffix so the dedup key is unique
        // (same as Kafka path: "28-recv" vs "28")
        String receiverTxId = req.getTransactionId() != null
                ? req.getTransactionId() + "-recv"
                : java.util.UUID.randomUUID().toString();

        return TransactionCompletedEvent.builder()
                .eventId(java.util.UUID.randomUUID().toString())
                .eventType("TransactionCompleted")
                .eventVersion("1.0")
                .transactionId(receiverTxId)
                .timestamp(java.time.Instant.now())
                .amount(req.getAmount())
                .currency(req.getCurrency())
                .type("TRANSFER_RECEIVED")           // ← receiver-side type
                .status(req.getStatus())
                .sourceAccountNumber(req.getSourceAccountNumber())   // sender's account
                .targetAccountNumber(req.getTargetAccountNumber())   // receiver's account
                .username(receiverUsername.trim())   // ← receiver's username
                .note(req.getNote())
                .locale(req.getLocale())
                .metadata(meta)
                .build();
    }

    private java.util.Map<String, String> buildMetadata(TransactionNotificationRequest req) {
        java.util.Map<String, String> meta = new java.util.HashMap<>();
        meta.put("transport", "http");
        meta.put("source", "titan-core-banking");
        if (req.getUserEmail() != null && !req.getUserEmail().isBlank()) {
            meta.put("userEmail", req.getUserEmail());
        }
        if (req.getMetadata() != null) {
            meta.putAll(req.getMetadata());
        }
        return meta;
    }
}
