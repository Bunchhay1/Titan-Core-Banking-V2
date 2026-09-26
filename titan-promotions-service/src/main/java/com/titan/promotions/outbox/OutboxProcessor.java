package com.titan.promotions.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.titan.promotions.model.PromotionOutbox;
import com.titan.promotions.repository.PromotionOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxProcessor {
    
    private static final int MAX_RETRIES = 3;
    
    private final PromotionOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    
    @Scheduled(fixedDelay = 2000)  // Process every 2 seconds for fast reward delivery
    @Transactional("transactionManager")
    public void processPendingEvents() {
        List<PromotionOutbox> pending = outboxRepository.findByStatusOrderByCreatedAtAsc(PromotionOutbox.OutboxStatus.PENDING);
        
        for (PromotionOutbox outbox : pending) {
            try {
                kafkaTemplate.send("banking.rewards.granted", outbox.getEventId(), outbox.getPayload()).get();
                
                outbox.setStatus(PromotionOutbox.OutboxStatus.SENT);
                outbox.setProcessedAt(LocalDateTime.now());
                outboxRepository.save(outbox);
                
                log.info("Outbox event {} sent successfully", outbox.getEventId());
            } catch (Exception e) {
                outbox.setRetryCount(outbox.getRetryCount() + 1);
                
                if (outbox.getRetryCount() >= MAX_RETRIES) {
                    outbox.setStatus(PromotionOutbox.OutboxStatus.FAILED);
                    log.error("Outbox event {} failed after {} retries", outbox.getEventId(), MAX_RETRIES, e);
                } else {
                    log.warn("Outbox event {} failed, retry {}/{}", outbox.getEventId(), outbox.getRetryCount(), MAX_RETRIES);
                }
                
                outboxRepository.save(outbox);
            }
        }
    }
}
