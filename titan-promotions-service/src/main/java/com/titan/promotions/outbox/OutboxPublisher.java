package com.titan.promotions.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    List<OutboxEvent> findTop100ByStatusOrderByCreatedAtAsc(OutboxEvent.Status status);
}

@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxPublisher {
    
    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    
    @Scheduled(fixedRate = 1000) // Every second
    @Transactional("transactionManager")
    public void publishPendingEvents() {
        List<OutboxEvent> pending = repository.findTop100ByStatusOrderByCreatedAtAsc(OutboxEvent.Status.PENDING);
        
        pending.forEach(event -> {
            try {
                kafkaTemplate.send(event.getEventType(), event.getPayload()).get();
                
                event.setStatus(OutboxEvent.Status.PUBLISHED);
                event.setPublishedAt(LocalDateTime.now());
                repository.save(event);
                
                log.debug("Published outbox event: id={}, type={}", event.getId(), event.getEventType());
            } catch (Exception e) {
                log.error("Failed to publish outbox event: id={}", event.getId(), e);
                event.setStatus(OutboxEvent.Status.FAILED);
                repository.save(event);
            }
        });
    }
}
