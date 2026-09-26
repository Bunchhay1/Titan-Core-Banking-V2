package com.titan.promotions.resilience;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class DeadLetterQueueHandler {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String mainTopic;
    private final String poisonTopic;

    public DeadLetterQueueHandler(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${spring.kafka.consumer.topic:banking.transactions.completed}") String mainTopic,
            @Value("${spring.kafka.dlq.poison-topic:banking.transactions.poison}") String poisonTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.mainTopic = mainTopic;
        this.poisonTopic = poisonTopic;
    }

    /**
     * Listen on the DLQ topic — must match KafkaErrorHandlingConfig.dlqTopic
     * which reads from spring.kafka.dlq.topic (default: banking.transactions.dlq).
     */
    @KafkaListener(
        topics = "${spring.kafka.dlq.topic:banking.transactions.dlq}",
        groupId = "dlq-recovery"
    )
    public void handleDeadLetter(
            String message,
            @Header(value = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String errorHeader,
            @Header(value = "retry-count", required = false) Integer retryCount) {

        String error = errorHeader != null ? errorHeader : "Unknown error";
        int currentRetry = retryCount != null ? retryCount : 0;

        log.warn("[DLQ] Received dead-letter message: attempt={}, error={}", currentRetry + 1, error);
        log.debug("[DLQ] Payload: {}", message);

        if (currentRetry >= 3) {
            log.error("[DLQ] ❌ Message exceeded max retries ({}), routing to poison queue: {}",
                currentRetry, poisonTopic);
            kafkaTemplate.send(poisonTopic, message);
            return;
        }

        // Exponential back-off before retry: 1s, 2s, 4s
        long backoffMs = (long) Math.pow(2, currentRetry) * 1000;
        log.info("[DLQ] Retrying message in {}ms (attempt {}/3) → topic={}",
            backoffMs, currentRetry + 1, mainTopic);

        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[DLQ] Interrupted during back-off sleep");
        }

        kafkaTemplate.send(mainTopic, message);
    }
}
