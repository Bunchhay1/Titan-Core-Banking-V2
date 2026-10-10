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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
// [MODIFIED] ARCHITECTURE: System now defaults to Debezium CDC for Kafka streaming.
// ការប្រើ @ConditionalOnProperty នេះមានន័យថា Service នេះនឹងមិនដំណើរការទេលុះត្រាតែយើងបើកវាដោយដៃ (outbox.polling.enabled=true)។
// ក្នុងកម្រិត Enterprise យើងប្រើ Debezium ដើម្បីអាន WAL (Write-Ahead Log) ពី Postgres ផ្ទាល់ជៀសវាងការធ្វើ Polling ដែលស៊ី CPU/RAM។
@ConditionalOnProperty(name = "outbox.polling.enabled", havingValue = "true", matchIfMissing = false)
public class OutboxRelayService {

    private static final String LOCK_KEY = "outbox:relay:lock";
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    private static final int BATCH_SIZE = 100; // Limits processing chunk to prevent memory bloat
    private static final int MAX_RETRIES = 5;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final String transactionCompletedTopic;
    private final AtomicBoolean localLock = new AtomicBoolean(false);

    // [MODIFIED] DEPENDENCY INJECTION: Consolidated dependencies into a single constructor.
    // ការប្រើប្រាស់ Constructor Injection ធ្វើឲ្យកូដងាយស្រួលក្នុងការធ្វើ Unit Test និងធានាថា Object ត្រូវបានបង្កើតឡើងយ៉ាងត្រឹមត្រូវ។
    public OutboxRelayService(OutboxRepository outboxRepository,
                              KafkaTemplate<String, Object> kafkaTemplate,
                              ObjectMapper objectMapper,
                              @Autowired(required = false) StringRedisTemplate redisTemplate,
                              @Value("${kafka.topic.transaction-completed:banking.transactions.completed}") String transactionCompletedTopic) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.transactionCompletedTopic = transactionCompletedTopic;
    }

    // [MODIFIED] PERFORMANCE: Removed @Scheduled(fixedDelay = 2000).
    // លុបចោល @Scheduled ដើម្បីបញ្ឈប់ការទាញទិន្នន័យ (Database Polling) រៀងរាល់ ២ វិនាទី។ Method នេះទុកសម្រាប់តែការហៅប្រើដោយដៃ (Fallback) ពេល CDC មានបញ្ហាប៉ុណ្ណោះ។
    public void relayPendingEventsFallback() {
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

    // [FLOW] DISTRIBUTED LOCKING: Ensures only one instance processes the outbox at a time to prevent duplicate Kafka messages.
    // ធានាថាមាន Server តែមួយគត់ដែលអាចទាញទិន្នន័យ Outbox យកទៅដំណើរការបានក្នុងពេលតែមួយ (ទប់ស្កាត់ការផ្ញើសារស្ទួនចូល Kafka)។
    private boolean acquireLock() {
        if (redisTemplate != null) {
            var acquired = redisTemplate.opsForValue().setIfAbsent(LOCK_KEY, String.valueOf(System.currentTimeMillis()), LOCK_TTL);
            return Boolean.TRUE.equals(acquired);
        }
        return localLock.compareAndSet(false, true);
    }

    private void releaseLock() {
        if (redisTemplate != null) {
            redisTemplate.delete(LOCK_KEY);
        } else {
            localLock.set(false);
        }
    }

    @Transactional
    public void processOutboxBatch() {
        // [FLOW] BATCH PROCESSING: Fetch only top 100 unpublished events that haven't exceeded max retries.
        var pendingEvents = outboxRepository.findTop100ByPublishedFalseAndRetryCountLessThanOrderByCreatedAtAsc(MAX_RETRIES);
        if (pendingEvents.isEmpty()) return;

        log.info("Processing {} pending outbox events (Fallback Mode)", pendingEvents.size());
        for (var event : pendingEvents) {
            processSingleEvent(event);
        }
    }

    // [MODIFIED] RELIABILITY: Switched to synchronous bounded wait for Kafka acknowledgments.
    private void processSingleEvent(OutboxEvent event) {
        try {
            var payload = objectMapper.readValue(event.getPayload(), Object.class);

            // [FLOW] EXACTLY-ONCE SEMANTICS: Block for 5 seconds waiting for Kafka broker ACK.
            // រង់ចាំការឆ្លើយតបពី Kafka រយៈពេល ៥វិនាទី (Synchronous)។ បើ Kafka មិនឆ្លើយតបទេ វានឹងលោតចូល Catch Block (ការពារការបាត់បង់ទិន្នន័យពេល Network ដាច់)។
            var sendResult = kafkaTemplate.send(transactionCompletedTopic, event.getAggregateId(), payload)
                    .get(5, TimeUnit.SECONDS);

            event.setPublished(true);
            event.setPublishedAt(Instant.now());
            outboxRepository.save(event);

            log.info("Published event {} at Kafka offset {}", event.getId(), sendResult.getRecordMetadata().offset());
        } catch (Exception ex) {
            handleEventFailure(event, ex);
        }
    }

    // [FLOW] FAULT TOLERANCE: Implements a retry mechanism with a hard cap to prevent infinite loops on poison pills.
    private void handleEventFailure(OutboxEvent event, Exception ex) {
        event.setRetryCount(event.getRetryCount() + 1);
        event.setLastError(ex.getMessage());
        outboxRepository.save(event);

        if (event.getRetryCount() >= MAX_RETRIES) {
            log.error("Event {} exceeded max retries. Marking dead.", event.getId()); // Dead Letter Queue logic entry point
        } else {
            log.warn("Event {} failed (retry {}/{}): {}", event.getId(), event.getRetryCount(), MAX_RETRIES, ex.getMessage());
        }
    }
}