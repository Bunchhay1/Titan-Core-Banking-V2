package com.titan.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base Event Consumer
 * Provides common event processing logic with:
 * - Idempotency (duplicate detection)
 * - Retry with exponential backoff
 * - Dead Letter Queue routing
 * - Manual offset management
 */
@Slf4j
public abstract class BaseEventConsumer<T> {
    
    @Autowired
    protected ObjectMapper objectMapper;
    
    @Autowired(required = false)
    protected DLQProducer dlqProducer;
    
    // In-memory cache for duplicate detection (eventId -> timestamp)
    // In production, consider using Redis with TTL
    private final Map<String, Long> processedEvents = new ConcurrentHashMap<>();
    private static final long EVENT_TTL_MS = 3600000; // 1 hour
    
    // Retry configuration
    private static final int MAX_RETRIES = 3;
    private static final long[] BACKOFF_DELAYS_MS = {1000, 2000, 4000}; // 1s, 2s, 4s
    
    /**
     * Abstract method to be implemented by concrete consumers
     * @param event The deserialized event object
     */
    protected abstract void processEvent(T event) throws Exception;
    
    /**
     * Get the event class type for deserialization
     */
    protected abstract Class<T> getEventClass();
    
    /**
     * Get the event ID from the event object for idempotency
     */
    protected abstract String getEventId(T event);
    
    /**
     * Main event consumption logic with error handling
     */
    protected void consumeEvent(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        String eventJson = record.value();
        String topic = record.topic();
        int partition = record.partition();
        long offset = record.offset();
        
        log.info("Received event from topic={}, partition={}, offset={}", topic, partition, offset);
        
        T event = null;
        String eventId = null;
        
        try {
            // Deserialize event
            event = objectMapper.readValue(eventJson, getEventClass());
            eventId = getEventId(event);
            
            // Check for duplicate
            if (isDuplicate(eventId)) {
                log.info("Skipping duplicate event: {}", eventId);
                acknowledgment.acknowledge();
                return;
            }
            
            // Process with retry
            processWithRetry(event, eventJson, topic);
            
            // Mark as processed
            markAsProcessed(eventId);
            
            // Acknowledge offset
            acknowledgment.acknowledge();
            log.info("Successfully processed event: {}", eventId);
            
        } catch (Exception e) {
            log.error("Failed to process event after {} retries: {}", MAX_RETRIES, e.getMessage(), e);
            
            // Send to DLQ
            if (dlqProducer != null && eventId != null) {
                dlqProducer.sendToDLQ(eventJson, topic, e.getMessage(), MAX_RETRIES);
            }
            
            // Acknowledge to prevent reprocessing
            acknowledgment.acknowledge();
        } finally {
            // Cleanup old entries from cache
            cleanupProcessedEvents();
        }
    }
    
    /**
     * Process event with exponential backoff retry
     */
    private void processWithRetry(T event, String eventJson, String topic) throws Exception {
        Exception lastException = null;
        
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                processEvent(event);
                return; // Success
            } catch (Exception e) {
                lastException = e;
                log.warn("Processing attempt {} failed for event: {}", attempt + 1, e.getMessage());
                
                if (attempt < MAX_RETRIES - 1) {
                    // Wait before retry
                    long delay = BACKOFF_DELAYS_MS[attempt];
                    log.info("Retrying in {}ms...", delay);
                    Thread.sleep(delay);
                }
            }
        }
        
        // All retries exhausted
        throw lastException;
    }
    
    /**
     * Check if event has already been processed
     */
    private boolean isDuplicate(String eventId) {
        Long processedTime = processedEvents.get(eventId);
        if (processedTime != null) {
            // Check if still within TTL
            if (System.currentTimeMillis() - processedTime < EVENT_TTL_MS) {
                return true;
            } else {
                // Expired, remove from cache
                processedEvents.remove(eventId);
            }
        }
        return false;
    }
    
    /**
     * Mark event as processed
     */
    private void markAsProcessed(String eventId) {
        processedEvents.put(eventId, System.currentTimeMillis());
    }
    
    /**
     * Cleanup expired entries from processed events cache
     */
    private void cleanupProcessedEvents() {
        long now = System.currentTimeMillis();
        processedEvents.entrySet().removeIf(entry -> 
            now - entry.getValue() > EVENT_TTL_MS
        );
    }
}
