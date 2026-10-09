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
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayService {

    private static final String LOCK_KEY = "outbox:relay:lock";
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    private static final int BATCH_SIZE = 100;
    private static final int MAX_RETRIES = 5;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final String transactionCompletedTopic;
    private final AtomicBoolean localLock = new AtomicBoolean(false);

    // [MODIFIED] Consolidated all dependencies into a single constructor.
    // ប្រមូលផ្តុំការទាញយក Dependencies ទាំងអស់តាមរយៈ Constructor តែមួយ ដើម្បីធានាថា Class នេះអាចអានបានស្រួល និងមិនមានបញ្ហាពេលធ្វើ Unit Test។
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

    // [MODIFIED] Fixed Transactional proxy issue and concurrency hazards.
    // ជួសជុលបញ្ហា @Transactional ដោយយក Method នេះមកជា public ដើម្បីឲ្យ Spring Proxy អាចចាប់បានត្រឹមត្រូវពេលធ្វើការជាមួយ Database។
    @Transactional
    public void processOutboxBatch() {
        var pendingEvents = outboxRepository.findTop100ByPublishedFalseAndRetryCountLessThanOrderByCreatedAtAsc(MAX_RETRIES);

        if (pendingEvents.isEmpty()) return;

        log.info("Processing {} pending outbox events", pendingEvents.size());

        for (var event : pendingEvents) {
            processSingleEvent(event);
        }
    }

    // [MODIFIED] Switched from Async callback to bounded synchronous publish for outbox safety.
    // ប្តូរពីការផ្ញើតាមបែប Async ទៅជា Synchronous (រង់ចាំលទ្ធផលអតិបរមា ៥ វិនាទី) ដើម្បីការពារកុំឲ្យ Thread ផ្សេងមកសរសេរជាន់លើ Entity ដែលកំពុងបើកក្នុង Transaction តែមួយ។
    private void processSingleEvent(OutboxEvent event) {
        try {
            var payload = objectMapper.readValue(event.getPayload(), Object.class);

            // Block with a timeout to guarantee delivery confirmation before updating the DB state
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

    private void handleEventFailure(OutboxEvent event, Exception ex) {
        event.setRetryCount(event.getRetryCount() + 1);
        event.setLastError(ex.getMessage());
        outboxRepository.save(event);

        if (event.getRetryCount() >= MAX_RETRIES) {
            log.error("Event {} exceeded max retries. Marking dead.", event.getId());
        } else {
            log.warn("Event {} failed (retry {}/{}): {}", event.getId(), event.getRetryCount(), MAX_RETRIES, ex.getMessage());
        }
    }
}