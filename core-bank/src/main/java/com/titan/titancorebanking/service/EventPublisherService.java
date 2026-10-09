package com.titan.titancorebanking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.titan.titancorebanking.event.kafka.TransactionCompletedEvent;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.OutboxEvent;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.repository.OutboxRepository;
import com.titan.titancorebanking.service.imple.ExchangeRateService;
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
    private final ExchangeRateService exchangeRateService;
    private final HttpClient httpClient;

    @Value("${kafka.enabled:false}")
    private boolean kafkaEnabled;

    @Value("${notification.service.url:http://localhost:8084}")
    private String notificationServiceUrl;

    public EventPublisherService(OutboxRepository outboxRepository,
                                 ObjectMapper objectMapper,
                                 ExchangeRateService exchangeRateService) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.exchangeRateService = exchangeRateService;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public void publishTransactionCompletedEvent(Transaction transaction) {
        try {
            // [MODIFIED] Streamlined the primary event generation.
            // បង្កើត Event សម្រាប់អ្នកផ្ញើ ឬម្ចាស់គណនីដើម ដោយកាត់បន្ថយភាពរញ៉េរញ៉ៃនៃលក្ខខណ្ឌ (If-Else) ។
            var senderEvent = buildPrimaryEvent(transaction);
            saveToOutbox(transaction.getId().toString(), senderEvent);

            TransactionCompletedEvent receiverEvent = null;

            // [MODIFIED] Extracted boolean checks for readability.
            // ត្រួតពិនិត្យថាវាជាប្រតិបត្តិការវេរប្រាក់ពិតប្រាកដ ឬអត់ មុននឹងបង្កើត Event សម្រាប់អ្នកទទួល។
            if (isTransferWithReceiver(transaction)) {
                receiverEvent = buildReceiverEvent(transaction);
                saveToOutbox(transaction.getId().toString() + "-recv", receiverEvent);
            }

            // [MODIFIED] Extracted HTTP fallback into a clean isolated method.
            if (!kafkaEnabled) {
                triggerHttpFallbackAsync(senderEvent, receiverEvent);
            }

        } catch (Exception e) {
            log.warn("Event publishing skipped for TX ID {}: {}", transaction.getId(), e.getMessage());
        }
    }

    // =========================================================================
    // PRIVATE HELPERS (Clean Code Extractions)
    // =========================================================================

    private void saveToOutbox(String aggregateId, TransactionCompletedEvent event) throws Exception {
        var outboxEvent = OutboxEvent.builder()
                .aggregateType("TRANSACTION")
                .aggregateId(aggregateId)
                .eventType("TransactionCompleted")
                .payload(objectMapper.writeValueAsString(event))
                .build();
        outboxRepository.save(outboxEvent);
        log.debug("Saved outbox event for TX ID: {}", aggregateId);
    }

    private boolean isTransferWithReceiver(Transaction transaction) {
        return transaction.getTransactionType() == com.titan.titancorebanking.enums.TransactionType.TRANSFER
                && transaction.getToAccount() != null
                && transaction.getFromAccount() != null;
    }

    private TransactionCompletedEvent buildPrimaryEvent(Transaction tx) {
        boolean isDeposit = tx.getFromAccount() == null && tx.getToAccount() != null;
        var ownerAccount = isDeposit ? tx.getToAccount() : tx.getFromAccount();
        var receiverAccount = tx.getToAccount();

        var ownerUsername = resolveUsername(ownerAccount, "SYSTEM");
        var receiverUsername = resolveUsername(receiverAccount, null);
        var currency = ownerAccount != null ? ownerAccount.getCurrency().name() : "USD";
        var correlationId = getCorrelationId();

        var metadata = new HashMap<String, String>();
        metadata.put("source", "titan-core-banking");
        metadata.put("channel", "mobile-app");

        if (isDeposit) {
            metadata.put("receiverName", ownerUsername);
            metadata.put("receiverAccount", ownerAccount.getAccountNumber());
            metadata.put("accountId", String.valueOf(ownerAccount.getId()));
            addEmailIfPresent(metadata, ownerAccount);
        } else {
            metadata.put("senderName", ownerUsername);
            metadata.put("senderAccount", ownerAccount != null ? ownerAccount.getAccountNumber() : "");
            if (receiverUsername != null) metadata.put("receiverName", receiverUsername);
            if (receiverAccount != null) metadata.put("receiverAccount", receiverAccount.getAccountNumber());

            if (ownerAccount != null) {
                metadata.put("accountId", String.valueOf(ownerAccount.getId()));
                addEmailIfPresent(metadata, ownerAccount);
            }
        }

        return TransactionCompletedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .transactionId(String.valueOf(tx.getId()))
                .timestamp(Instant.now())
                .correlationId(correlationId)
                .amount(tx.getAmount())
                .currency(currency)
                .type(tx.getTransactionType().name())
                .status(tx.getStatus().name())
                .sourceAccountNumber(tx.getFromAccount() != null ? tx.getFromAccount().getAccountNumber() : null)
                .targetAccountNumber(tx.getToAccount() != null ? tx.getToAccount().getAccountNumber() : null)
                .username(ownerUsername)
                .note(tx.getNote())
                .metadata(metadata)
                .build();
    }

    private TransactionCompletedEvent buildReceiverEvent(Transaction tx) {
        var receiver = tx.getToAccount();
        var sender = tx.getFromAccount();

        var receiverUsername = resolveUsername(receiver, receiver.getAccountNumber());
        var senderUsername = resolveUsername(sender, sender.getAccountNumber());
        var correlationId = getCorrelationId();

        var receiverAmount = tx.getAmount();
        if (sender != null && sender.getCurrency() != receiver.getCurrency()) {
            receiverAmount = exchangeRateService.convert(tx.getAmount(), sender.getCurrency(), receiver.getCurrency());
        }

        var metadata = new HashMap<String, String>();
        metadata.put("source", "titan-core-banking");
        metadata.put("channel", "mobile-app");
        metadata.put("senderName", senderUsername);
        metadata.put("receiverName", receiverUsername);
        metadata.put("senderAccount", sender.getAccountNumber());
        metadata.put("receiverAccount", receiver.getAccountNumber());
        metadata.put("accountId", String.valueOf(receiver.getId()));
        addEmailIfPresent(metadata, receiver);

        return TransactionCompletedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .transactionId(tx.getId().toString() + "-recv")
                .timestamp(Instant.now())
                .correlationId(correlationId)
                .amount(receiverAmount)
                .currency(receiver.getCurrency().name())
                .type("TRANSFER_RECEIVED")
                .status(tx.getStatus().name())
                .sourceAccountNumber(sender.getAccountNumber())
                .targetAccountNumber(receiver.getAccountNumber())
                .username(receiverUsername)
                .note(tx.getNote())
                .metadata(metadata)
                .build();
    }

    private String resolveUsername(Account account, String defaultValue) {
        if (account != null && account.getUser() != null && account.getUser().getUsername() != null) {
            return account.getUser().getUsername().trim();
        }
        return defaultValue;
    }

    private void addEmailIfPresent(Map<String, String> metadata, Account account) {
        if (account.getUser() != null && account.getUser().getEmail() != null && !account.getUser().getEmail().isBlank()) {
            metadata.put("userEmail", account.getUser().getEmail().trim());
        }
    }

    private String getCorrelationId() {
        var mdcVal = MDC.get("correlationId");
        return mdcVal != null ? mdcVal : UUID.randomUUID().toString();
    }

    // =========================================================================
    // HTTP FALLBACK
    // =========================================================================

    private void triggerHttpFallbackAsync(TransactionCompletedEvent senderEvent, TransactionCompletedEvent receiverEvent) {
        Thread.ofVirtual().name("notify-http-", 0).start(() -> {
            sendHttpNotification(senderEvent);
            if (receiverEvent != null) {
                sendHttpNotification(receiverEvent);
            }
        });
    }

    private void sendHttpNotification(TransactionCompletedEvent event) {
        try {
            var url = notificationServiceUrl + "/api/notify/transaction";
            var json = objectMapper.writeValueAsString(event);
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(5))
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                log.info("[HTTP] Notification accepted by notification service: txId={} type={}", event.getTransactionId(), event.getType());
            } else {
                log.warn("[HTTP] Notification service returned {}: txId={} body={}", response.statusCode(), event.getTransactionId(), response.body());
            }
        } catch (Exception e) {
            log.warn("[HTTP] Failed to notify for txId={}: {}", event.getTransactionId(), e.getMessage());
        }
    }
}