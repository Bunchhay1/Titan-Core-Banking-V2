package com.titan.notifications.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.titan.notifications.event.TransactionCompletedEvent;
import com.titan.notifications.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

/**
 * Kafka consumer — only active when kafka.enabled=true.
 * When KAFKA_ENABLED=false (Render free tier without Confluent Cloud),
 * this bean is skipped entirely so startup doesn't crash trying to
 * connect to a Kafka broker that doesn't exist.
 *
 * In that case, core-banking calls POST /api/notify/transaction directly via HTTP.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true", matchIfMissing = false)
public class NotificationListener {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = "${spring.kafka.consumer.topic:banking.transactions.completed}",
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeEvent(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        try {
            TransactionCompletedEvent event = objectMapper.readValue(record.value(), TransactionCompletedEvent.class);
            log.info("📧 [Kafka] Processing notification: txId={}, amount={}", event.getTransactionId(), event.getAmount());
            notificationService.sendNotifications(event);
            acknowledgment.acknowledge();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Poison message — malformed JSON that can never be processed successfully.
            // Acknowledge immediately so the consumer does not get stuck in an infinite
            // retry loop on this bad record.  The error handler (DLQ) will capture it
            // separately if configured; here we at least keep the consumer moving.
            log.error("💀 [Kafka] Poison message — cannot deserialize, acknowledging to skip: key={}, error={}, payload={}",
                    record.key(), e.getMessage(), record.value());
            acknowledgment.acknowledge();
        } catch (Exception e) {
            // Transient error (e.g., DB unavailable).  Do NOT acknowledge — the record
            // will be retried by Kafka's error-handler / back-off policy so we get
            // another attempt once the downstream issue is resolved.
            log.error("❌ [Kafka] Failed to process notification event: key={}, error={}", record.key(), e.getMessage(), e);
        }
    }
}
