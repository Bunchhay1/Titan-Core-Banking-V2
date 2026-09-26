package com.titan.titancorebanking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.titan.titancorebanking.model.OutboxEvent;
import com.titan.titancorebanking.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Outbox Relay Service — polls the outbox table and publishes events to Kafka.
 *
 * Redis is OPTIONAL:
 *   - When Redis is available: uses distributed lock (prevents duplicate processing
 *     across multiple instances).
 *   - When Redis is NOT available (Render free tier): falls back to an in-process
 *     AtomicBoolean lock (safe for single-instance deployments).
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayService {

    private static final String LOCK_KEY   = "outbox:relay:lock";
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    private static final int BATCH_SIZE    = 100;
    private static final int MAX_RETRIES   = 5;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    // In-process fallback lock — used when Redis is unavailable
    private final AtomicBoolean localLock = new AtomicBoolean(false);

    // Redis is optional — injected only if a RedisTemplate bean exists
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Value("${kafka.topic.transaction-completed:banking.transactions.completed}")
    private String transactionCompletedTopic;

    public OutboxRelayService(OutboxRepository outboxRepository,
                              KafkaTemplate<String, Object> kafkaTemplate,
                              ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate    = kafkaTemplate;
        this.objectMapper     = objectMapper;
    }

    @Scheduled(fixedDelay = 2000)
    public void relayPendingEvents() {
        if (!acquireLock()) {
            log.trace("Outbox relay lock held by another process. Skipping.");
            return;
        }
        try {
            processOutboxBatch();
        } finally {
            releaseLock();
        }
    }

    // ── Lock helpers ─────────────────────────────────────────────────────

    private boolean acquireLock() {
        if (redisTemplate != null) {
            // Distributed lock via Redis
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(LOCK_KEY, String.valueOf(System.currentTimeMillis()), LOCK_TTL);
            return Boolean.TRUE.equals(acquired);
        }
        // In-process fallback (single instance — Render free tier)
        return localLock.compareAndSet(false, true);
    }

    private void releaseLock() {
        if (redisTemplate != null) {
            redisTemplate.delete(LOCK_KEY);
        } else {
            localLock.set(false);
        }
    }

    // ── Batch processing ─────────────────────────────────────────────────

    @Transactional
    protected void processOutboxBatch() {
        List<OutboxEvent> pending = outboxRepository
                .findTop100ByPublishedFalseAndRetryCountLessThanOrderByCreatedAtAsc(MAX_RETRIES);

        if (pending.isEmpty()) return;

        log.info("📤 Processing {} pending outbox events", pending.size());

        for (OutboxEvent event : pending) {
            publishToKafka(event);
        }
    }

    private void publishToKafka(OutboxEvent event) {
        try {
            Object payload = objectMapper.readValue(event.getPayload(), Object.class);

            CompletableFuture<SendResult<String, Object>> future =
                    kafkaTemplate.send(transactionCompletedTopic, event.getAggregateId(), payload);

            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    handleFailure(event, ex);
                } else {
                    handleSuccess(event, result);
                }
            });

        } catch (Exception e) {
            handleFailure(event, e);
        }
    }

    @Transactional
    protected void handleSuccess(OutboxEvent event, SendResult<String, Object> result) {
        event.setPublished(true);
        event.setPublishedAt(Instant.now());
        outboxRepository.save(event);
        log.info("✅ Published event {} → Kafka offset {}",
                event.getId(), result.getRecordMetadata().offset());
    }

    @Transactional
    protected void handleFailure(OutboxEvent event, Throwable ex) {
        event.setRetryCount(event.getRetryCount() + 1);
        event.setLastError(ex.getMessage());
        outboxRepository.save(event);

        if (event.getRetryCount() >= MAX_RETRIES) {
            log.error("❌ Event {} exceeded max retries. Marking dead.", event.getId());
        } else {
            log.warn("⚠️ Event {} failed (retry {}/{}): {}",
                    event.getId(), event.getRetryCount(), MAX_RETRIES, ex.getMessage());
        }
    }
}
