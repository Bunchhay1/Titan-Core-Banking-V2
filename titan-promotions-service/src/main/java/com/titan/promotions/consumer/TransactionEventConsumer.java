package com.titan.promotions.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.titan.promotions.event.TransactionCompletedEvent;
import com.titan.promotions.service.PromotionEvaluationService;
import com.titan.promotions.service.PromotionService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class TransactionEventConsumer {

    private final PromotionEvaluationService evaluationService;
    private final PromotionService promotionService;
    private final ObjectMapper objectMapper;
    private final Counter consumerLagCounter;
    private final Counter eventsReceivedCounter;
    private final Counter eventsProcessedCounter;

    public TransactionEventConsumer(PromotionEvaluationService evaluationService,
                                    PromotionService promotionService,
                                    ObjectMapper objectMapper,
                                    MeterRegistry meterRegistry) {
        this.evaluationService = evaluationService;
        this.promotionService = promotionService;
        this.objectMapper = objectMapper;
        this.consumerLagCounter = Counter.builder("kafka.consumer.lag")
            .description("Kafka consumer lag")
            .register(meterRegistry);
        this.eventsReceivedCounter = Counter.builder("kafka.events.received")
            .description("Total Kafka transaction events received")
            .register(meterRegistry);
        this.eventsProcessedCounter = Counter.builder("kafka.events.processed")
            .description("Total Kafka transaction events successfully processed")
            .register(meterRegistry);
    }

    @KafkaListener(
        topics = "${spring.kafka.consumer.topic:banking.transactions.completed}",
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeEvent(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        eventsReceivedCounter.increment();

        log.debug("[KAFKA] Received record: topic={}, partition={}, offset={}, key={}",
            record.topic(), record.partition(), record.offset(), record.key());
        log.debug("[KAFKA] Raw payload: {}", record.value());

        try {
            TransactionCompletedEvent event = objectMapper.readValue(record.value(), TransactionCompletedEvent.class);

            log.info("[KAFKA] ▶ Processing transaction event: transactionId={}, type={}, amount={}, currency={}, accountId={}",
                event.getTransactionId(),
                event.getTransactionType(),
                event.getAmount(),
                event.getCurrency(),
                event.getAccountId());

            // Path 1: Generic rule-engine evaluation (all campaigns in DB via SpEL)
            log.debug("[KAFKA] Running generic rule-engine evaluation for transactionId={}", event.getTransactionId());
            evaluationService.evaluateTransaction(event);

            // Path 2: Full promotion suite (deposit bonus, referral, cashback, coin points, etc.)
            log.debug("[KAFKA] Running full promotion suite for transactionId={}", event.getTransactionId());
            promotionService.evaluatePromotions(event);

            acknowledgment.acknowledge();
            eventsProcessedCounter.increment();

            log.info("[KAFKA] ✅ Transaction event processed and acknowledged: transactionId={}", event.getTransactionId());

        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("[KAFKA] ❌ Failed to deserialize transaction event: key={}, error={}, payload={}",
                record.key(), e.getMessage(), record.value(), e);
            consumerLagCounter.increment();
            // Do NOT acknowledge — let error handler send to DLQ
        } catch (Exception e) {
            log.error("[KAFKA] ❌ Failed to process transaction event: key={}, transactionId={}, error={}",
                record.key(),
                extractTransactionId(record.value()),
                e.getMessage(), e);
            consumerLagCounter.increment();
            // Do NOT acknowledge — let error handler send to DLQ
        }
    }

    /**
     * Best-effort extraction of transactionId from raw JSON for error logging.
     */
    private String extractTransactionId(String payload) {
        if (payload == null) return "unknown";
        try {
            int idx = payload.indexOf("\"transactionId\"");
            if (idx < 0) return "unknown";
            int colon = payload.indexOf(':', idx);
            int quote1 = payload.indexOf('"', colon + 1);
            int quote2 = payload.indexOf('"', quote1 + 1);
            return payload.substring(quote1 + 1, quote2);
        } catch (Exception e) {
            return "unknown";
        }
    }
}
