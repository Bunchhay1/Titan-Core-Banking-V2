package com.titan.titancorebanking.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.titan.titancorebanking.dto.RewardEventDto;
import com.titan.titancorebanking.service.RewardDomainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Enterprise Reward Consumer (Infrastructure Layer)
 * Handles ONLY messaging contracts, deserialization, and event routing.
 * No Database Transactions are held during I/O operations.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RewardConsumer {

    private final RewardDomainService rewardDomainService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private static final String ACK_TOPIC = "banking.rewards.acknowledgment";
    private static final String NOTIFICATION_TOPIC = "banking.transactions.completed";

    @KafkaListener(
            topics = "banking.rewards.granted",
            groupId = "core-banking-rewards"
    )
    public void consumeRewardGranted(ConsumerRecord<String, String> record) {
        log.info("[REWARD] Consuming event: key={}, offset={}", record.key(), record.offset());

        try {
            // 1. Strong Type Validation (សុវត្ថិភាពប្រភេទការទិន្នន័យ)
            RewardEventDto event = objectMapper.readValue(record.value(), RewardEventDto.class);

            if (!event.isValid()) {
                log.warn("[REWARD] Malformed payload. EventId: {}", event.eventId());
                publishAck(event.eventId(), "FAILED", "Invalid payload parameters");
                return;
            }

            // 2. Delegate to Domain Service (គ្មាន Kafka I/O កំពុងកាន់ DB Lock ទេ)
            RewardDomainService.RewardResult result = rewardDomainService.processRewardAtomic(event);

            // 3. Publish I/O strictly AFTER DB Transaction is fully committed
            if (result.isProcessed()) {
                publishAck(event.eventId(), "SUCCESS", null);
                publishNotification(result);
            } else if (result.isDuplicate()) {
                log.info("[REWARD] Event {} was duplicate. Sending ACK.", event.eventId());
                publishAck(event.eventId(), "SUCCESS", "Duplicate skipped");
            }

        } catch (Exception e) {
            log.error("[REWARD] Failed event processing. Key={}", record.key(), e);
            throw new RuntimeException("Triggering Kafka Retry/DLQ", e);
        }
    }

    private void publishAck(String eventId, String status, String error) {
        if (eventId == null) return;
        var ack = Map.of(
                "rewardEventId", eventId,
                "status", status,
                "error", error == null ? "" : error
        );
        kafkaTemplate.send(ACK_TOPIC, eventId, ack);
    }

    private void publishNotification(RewardDomainService.RewardResult result) {
        var event = Map.of(
                "eventId", result.eventId(),
                "eventType", "TransactionCompleted",
                "amount", result.amount(),
                "currency", result.currency(),
                "newBalance", result.newBalance(),
                "status", "SUCCESS"
        );
        kafkaTemplate.send(NOTIFICATION_TOPIC, result.eventId(), event);
    }
}