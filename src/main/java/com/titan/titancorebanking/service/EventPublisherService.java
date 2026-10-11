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
import org.springframework.scheduling.annotation.Async;
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

    @Value("${kafka.enabled:false}")
    private boolean kafkaEnabled;

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

    public void publishTransactionCompletedEvent(Transaction transaction) {
        try {
            TransactionCompletedEvent event = buildTransactionCompletedEvent(transaction);
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
                    .payload(objectMapper.writeValueAsString(event))
                    .build();
            outboxRepository.save(outboxEvent);
            log.debug("Saved outbox event for TX ID: {} (Sender)", transaction.getId());

            if (receiverEvent != null) {
                OutboxEvent receiverOutboxEvent = OutboxEvent.builder()
                        .aggregateType("TRANSACTION")
                        .aggregateId(transaction.getId().toString() + "-recv")
                        .eventType("TransactionCompleted")
                        .payload(objectMapper.writeValueAsString(receiverEvent))
                        .build();
                outboxRepository.save(receiverOutboxEvent);
                log.debug("Saved outbox event for TX ID: {} (Receiver)", transaction.getId());
            }

            if (!kafkaEnabled) {
                dispatchHttpNotificationsAsync(event, receiverEvent);
            }
        } catch (Exception e) {
            log.warn("  Event publishing skipped for TX ID {}: {}", transaction.getId(), e.getMessage());
        }
    }

    @Async
    protected void dispatchHttpNotificationsAsync(TransactionCompletedEvent senderEvent, TransactionCompletedEvent receiverEvent) {
        sendHttpNotification(senderEvent);
        if (receiverEvent != null) {
            sendHttpNotification(receiverEvent);
        }
    }

    private TransactionCompletedEvent buildReceiverEvent(Transaction tx) {
        Account receiver = tx.getToAccount();
        Account sender   = tx.getFromAccount();

        String receiverUsername = (receiver.getUser() != null && receiver.getUser().getUsername() != null)
                ? receiver.getUser().getUsername().trim() : receiver.getAccountNumber().trim();
        String senderUsername   = (sender.getUser() != null && sender.getUser().getUsername() != null)
                ? sender.getUser().getUsername().trim() : sender.getAccountNumber().trim();
        String receiverEmail    = (receiver.getUser() != null) ? receiver.getUser().getEmail() : null;
        String currency      = receiver.getCurrency().name();

        String correlationId = MDC.get("correlationId") != null
                ? MDC.get("correlationId") : UUID.randomUUID().toString();

        java.math.BigDecimal receiverAmount = tx.getAmount();
        if (sender != null && sender.getCurrency() != receiver.getCurrency()) {
            receiverAmount = exchangeRateService.convert(tx.getAmount(), sender.getCurrency(), receiver.getCurrency());
        }

        Map<String, String> metadata = new HashMap<>();
        metadata.put("source",              "titan-core-banking");
        metadata.put("channel",             "mobile-app");
        metadata.put("senderName",          senderUsername);
        metadata.put("receiverName",        receiverUsername);
        metadata.put("senderAccount",       sender.getAccountNumber());
        metadata.put("receiverAccount",     receiver.getAccountNumber());
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
        Account ownerAccount   = isDeposit ? tx.getToAccount() : tx.getFromAccount();
        Account receiverAccount = tx.getToAccount();

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

        Map<String, String> metadata = new HashMap<>();
        metadata.put("source",          "titan-core-banking");
        metadata.put("channel",         "mobile-app");

        if (isDeposit) {
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
                .sourceAccountNumber(tx.getFromAccount() != null ? tx.getFromAccount().getAccountNumber() : null)
                .targetAccountNumber(tx.getToAccount() != null ? tx.getToAccount().getAccountNumber() : null)
                .username(ownerUsername)
                .note(tx.getNote())
                .metadata(metadata)
                .build();
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

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                log.info("  [HTTP] Notification accepted by notification service: txId={} type={}",
                        event.getTransactionId(), event.getType());
            } else {
                log.warn("  [HTTP] Notification service returned {}: txId={} body={}",
                        response.statusCode(), event.getTransactionId(), response.body());
            }
        } catch (Exception e) {
            log.warn("  [HTTP] Failed to notify for txId={}: {}", event.getTransactionId(), e.getMessage());
        }
    }
}