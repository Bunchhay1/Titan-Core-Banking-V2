package com.titan.titancorebanking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.titan.titancorebanking.model.OutboxEvent;
import com.titan.titancorebanking.repository.OutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayService {

    private static final String LOCK_KEY = "outbox:relay:lock";
    private static final Duration LOCK_TTL = Duration.ofSeconds(15);
    private static final int MAX_RETRIES = 5;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean localLock = new AtomicBoolean(false);

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Value("${kafka.topic.transaction-completed:banking.transactions.completed}")
    private String transactionCompletedTopic;

    public OutboxRelayService(OutboxRepository outboxRepository,
                              KafkaTemplate<String, Object> kafkaTemplate,
                              ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 2000)
    public void relayPendingEvents() {
        if (!acquireDistributedLock()) {
            return;
        }

        try {
            processOutboxBatch();
        } finally {
            releaseDistributedLock();
        }
    }

    @Transactional(readOnly = true)
    protected void processOutboxBatch() {
        List<OutboxEvent> pending = outboxRepository
                .findTop100ByPublishedFalseAndRetryCountLessThanOrderByCreatedAtAsc(MAX_RETRIES);

        if (pending.isEmpty()) {
            return;
        }

        log.info("Relaying {} pending outbox records to Kafka", pending.size());

        for (OutboxEvent event : pending) {
            dispatchSingleEvent(event);
        }
    }

    private void dispatchSingleEvent(OutboxEvent event) {
        try {
            Object payload = objectMapper.readValue(event.getPayload(), Object.class);

            kafkaTemplate.send(transactionCompletedTopic, event.getAggregateId(), payload)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            markEventFailure(event.getId(), ex.getMessage());
                        } else {
                            long offset = result.getRecordMetadata().offset();
                            markEventSuccess(event.getId(), offset);
                        }
                    });
        } catch (Exception ex) {
            markEventFailure(event.getId(), "Serialization failure: " + ex.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markEventSuccess(Long eventId, long offset) {
        outboxRepository.findById(eventId).ifPresent(event -> {
            event.setPublished(true);
            event.setPublishedAt(Instant.now());
            outboxRepository.save(event);
            log.debug("Event {} published successfully at offset {}", eventId, offset);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markEventFailure(Long eventId, String errorMessage) {
        outboxRepository.findById(eventId).ifPresent(event -> {
            int retries = event.getRetryCount() + 1;
            event.setRetryCount(retries);
            event.setLastError(errorMessage);
            event.setLastRetryAt(Instant.now());
            outboxRepository.save(event);

            if (retries >= MAX_RETRIES) {
                log.error("Outbox event {} dead-lettered after {} failures: {}", eventId, retries, errorMessage);
            } else {
                log.warn("Outbox event {} failed (attempt {}/{}): {}", eventId, retries, MAX_RETRIES, errorMessage);
            }
        });
    }

    private boolean acquireDistributedLock() {
        try {
            if (redisTemplate != null) {
                Boolean acquired = redisTemplate.opsForValue()
                        .setIfAbsent(LOCK_KEY, String.valueOf(System.currentTimeMillis()), LOCK_TTL);
                return Boolean.TRUE.equals(acquired);
            }
        } catch (Exception e) {
            log.warn("Redis unreachable for Outbox lock, falling back to local memory lock");
        }
        return localLock.compareAndSet(false, true);
    }

    private void releaseDistributedLock() {
        try {
            if (redisTemplate != null) {
                redisTemplate.delete(LOCK_KEY);
            }
        } catch (Exception ignored) {
        } finally {
            localLock.set(false);
        }
    }
}