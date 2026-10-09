package com.titan.titancorebanking.controller;

import com.titan.titancorebanking.dto.response.OutboxStatusResponse;
import com.titan.titancorebanking.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Outbox Pattern Monitoring Controller.
 *
 * Provides operational visibility into the transactional outbox event publishing system.
 * This endpoint exposes sensitive infrastructure metrics and should be restricted to ADMIN users only.
 *
 * SECURITY FIX: Added @PreAuthorize("hasRole('ADMIN')") to prevent information disclosure.
 *
 * Information Disclosure Risk:
 * - Kafka event volume (publishedEvents, pendingEvents)
 * - System health indicators (failedEvents, avgPublishTimeSeconds)
 * - Redis lock state (relayLocked)
 * - Infrastructure timing metrics
 *
 * This data can be used by attackers to:
 * - Profile system load and find optimal attack times
 * - Identify system health issues for exploitation
 * - Understand event-driven architecture for targeted attacks
 * - Plan DoS attacks based on processing capacity
 */
@RestController
@RequestMapping("/api/admin/outbox")
@RequiredArgsConstructor
@Slf4j
public class OutboxMonitoringController {
    
    private final OutboxRepository outboxRepository;
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    
    /**
     * Get outbox event publishing status and health metrics.
     *
     * SECURITY FIX: Previously had NO authorization - any authenticated user could access.
     * Now restricted to ADMIN role only.
     *
     * Exposed Metrics:
     * - pendingEvents: Events waiting to be published to Kafka
     * - publishedEvents: Successfully published events (lifetime count)
     * - failedEvents: Events that exceeded retry limit (permanent failures)
     * - oldestPendingEvent: Age of oldest unpublished event (staleness indicator)
     * - avgPublishTimeSeconds: Average time from creation to publication (performance metric)
     * - relayLocked: Whether another instance is currently processing outbox (distributed lock state)
     *
     * Use Cases:
     * - Operations monitoring and alerting
     * - Performance troubleshooting
     * - Capacity planning
     * - Incident response
     *
     * @return Map containing outbox health metrics
     */
    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> getOutboxStatus() {
        log.debug("ADMIN accessing outbox status metrics");
        
        Long pending = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox_events WHERE published = FALSE AND retry_count < 5", Long.class);
        
        Long published = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox_events WHERE published = TRUE", Long.class);
        
        Long failed = outboxRepository.countFailedEvents(5);
        
        Instant oldest = jdbcTemplate.queryForObject(
            "SELECT MIN(created_at) FROM outbox_events WHERE published = FALSE", Instant.class);
        
        Double avgTime = jdbcTemplate.queryForObject(
            "SELECT AVG(EXTRACT(EPOCH FROM (published_at - created_at))) FROM outbox_events WHERE published = TRUE AND published_at > NOW() - INTERVAL '1 hour'", 
            Double.class);
        
        // Check if relay is locked (another instance processing)
        Boolean isLocked = redisTemplate.hasKey("outbox:relay:lock");
        
        Map<String, Object> status = new HashMap<>();
        status.put("pendingEvents", pending);
        status.put("publishedEvents", published);
        status.put("failedEvents", failed);
        status.put("oldestPendingEvent", oldest);
        status.put("avgPublishTimeSeconds", avgTime != null ? avgTime : 0.0);
        status.put("relayLocked", isLocked);
        status.put("timestamp", Instant.now());
        
        return status;
    }
}
