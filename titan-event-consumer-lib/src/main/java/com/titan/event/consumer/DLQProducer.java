package com.titan.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Dead Letter Queue Producer
 * Publishes failed events to DLQ topic with error metadata
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DLQProducer {
    
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    
    @Value("${spring.kafka.dlq.topic:banking.transactions.dlq}")
    private String dlqTopic;
    
    /**
     * Send failed event to Dead Letter Queue
     */
    public void sendToDLQ(String originalEvent, String originalTopic, String errorMessage, int retryCount) {
        try {
            DLQMessage dlqMessage = DLQMessage.builder()
                .originalEvent(originalEvent)
                .originalTopic(originalTopic)
                .errorMessage(errorMessage)
                .retryCount(retryCount)
                .failedAt(Instant.now().toString())
                .build();
            
            String dlqJson = objectMapper.writeValueAsString(dlqMessage);
            
            kafkaTemplate.send(dlqTopic, dlqJson)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to send message to DLQ: {}", ex.getMessage());
                    } else {
                        log.info("Sent failed event to DLQ topic: {}", dlqTopic);
                    }
                });
                
        } catch (Exception e) {
            log.error("Error creating DLQ message: {}", e.getMessage(), e);
        }
    }
    
    /**
     * DLQ Message wrapper
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DLQMessage {
        private String originalEvent;
        private String originalTopic;
        private String errorMessage;
        private int retryCount;
        private String failedAt;
    }
}
