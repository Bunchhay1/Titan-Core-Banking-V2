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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Slf4j
public class EventPublisherService {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final ExchangeRateService exchangeRateService;

    @Value("${kafka.enabled:false}")
    private boolean kafkaEnabled;

    @Value("${notification.service.url:http://localhost:8084}")
    private String notificationServiceUrl;

    // STAFF ENGINEER FIX: Shared HttpClient with connection pooling and strict timeouts
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    // STAFF ENGINEER FIX: Structured Virtual Thread Executor for HTTP fallbacks
    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public EventPublisherService(OutboxRepository outboxRepository,
                                 ObjectMapper objectMapper,
                                 ExchangeRateService exchangeRateService) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.exchangeRateService = exchangeRateService;
    }

    public void publishTransactionCompletedEvent(Transaction transaction) {
        try {
            TransactionCompletedEvent senderEvent = buildTransactionCompletedEvent(transaction);
            TransactionCompletedEvent receiverEvent = null;

            if (transaction.getTransactionType() == com.titan.titancorebanking.enums.TransactionType.TRANSFER
                    && transaction.getToAccount() != null
                    && transaction.getFromAccount() != null) {
                receiverEvent = buildReceiverEvent(transaction);
            }

            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateType("TRANSACTION")
                    .aggregateId(transaction.getId().toString())
                    .eventType("TransactionCompleted")
                    .payload(objectMapper.writeValueAsString(senderEvent))
                    .build();
            outboxRepository.save(outboxEvent);

            if (receiverEvent != null) {
                OutboxEvent receiverOutboxEvent = OutboxEvent.builder()
                        .aggregateType("TRANSACTION")
                        .aggregateId(transaction.getId().toString() + "-recv")
                        .eventType("TransactionCompleted")
                        .payload(objectMapper.writeValueAsString(receiverEvent))
                        .build();
                outboxRepository.save(receiverOutboxEvent);
            }

            if (!kafkaEnabled) {
                final TransactionCompletedEvent finalSenderEvent = senderEvent;
                final TransactionCompletedEvent finalReceiverEvent = receiverEvent;

                virtualThreadExecutor.submit(() -> {
                    sendHttpNotification(finalSenderEvent);
                    if (finalReceiverEvent != null) {
                        sendHttpNotification(finalReceiverEvent);
                    }
                });
            }
        } catch (Exception e) {
            log.error("CRITICAL: Failed to save outbox event for TX ID {}. Error: {}", transaction.getId(), e.getMessage());
        }
    }

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

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                log.warn("[HTTP Fallback] Notification service returned {}: txId={}", response.statusCode(), event.getTransactionId());
            }
        } catch (Exception e) {
            log.warn("[HTTP Fallback] Failed to notify for txId={}: {}", event.getTransactionId(), e.getMessage());
        }
    }

    // ... (buildTransactionCompletedEvent and buildReceiverEvent remain structurally identical, ensuring metadata is populated safely)

    private TransactionCompletedEvent buildReceiverEvent(Transaction tx) {
        Account receiver = tx.getToAccount();
        Account sender = tx.getFromAccount();

        String receiverUsername = (receiver.getUser() != null && receiver.getUser().getUsername() != null)
                ? receiver.getUser().getUsername().trim() : receiver.getAccountNumber().trim();
        String senderUsername = (sender.getUser() != null && sender.getUser().getUsername() != null)
                ? sender.getUser().getUsername().trim() : sender.getAccountNumber().trim();

        java.math.BigDecimal receiverAmount = tx.getAmount();
        if (sender != null && sender.getCurrency() != receiver.getCurrency()) {
            receiverAmount = exchangeRateService.convert(tx.getAmount(), sender.getCurrency(), receiver.getCurrency());
        }

        Map<String, String> metadata = new HashMap<>();
        metadata.put("source", "titan-core-banking");
        metadata.put("channel", "mobile-app");
        metadata.put("senderName", senderUsername);
        metadata.put("receiverName", receiverUsername);
        metadata.put("senderAccount", sender.getAccountNumber());
        metadata.put("receiverAccount", receiver.getAccountNumber());
        metadata.put("accountId", String.valueOf(receiver.getId()));

        return TransactionCompletedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("TransactionCompleted")
                .transactionId(tx.getId().toString() + "-recv")
                .timestamp(Instant.now())
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

    private TransactionCompletedEvent buildTransactionCompletedEvent(Transaction tx) {
        boolean isDeposit = tx.getFromAccount() == null && tx.getToAccount() != null;
        Account ownerAccount = isDeposit ? tx.getToAccount() : tx.getFromAccount();

        String ownerUsername = (ownerAccount != null && ownerAccount.getUser() != null && ownerAccount.getUser().getUsername() != null)
                ? ownerAccount.getUser().getUsername().trim() : "SYSTEM";

        Map<String, String> metadata = new HashMap<>();
        metadata.put("source", "titan-core-banking");
        metadata.put("channel", "mobile-app");
        if (ownerAccount != null) {
            metadata.put("accountId", String.valueOf(ownerAccount.getId()));
        }

        return TransactionCompletedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("TransactionCompleted")
                .transactionId(String.valueOf(tx.getId()))
                .timestamp(Instant.now())
                .amount(tx.getAmount())
                .currency(ownerAccount != null ? ownerAccount.getCurrency().name() : "USD")
                .type(tx.getTransactionType().name())
                .status(tx.getStatus().name())
                .sourceAccountNumber(tx.getFromAccount() != null ? tx.getFromAccount().getAccountNumber() : null)
                .targetAccountNumber(tx.getToAccount() != null ? tx.getToAccount().getAccountNumber() : null)
                .username(ownerUsername)
                .note(tx.getNote())
                .metadata(metadata)
                .build();
    }
}