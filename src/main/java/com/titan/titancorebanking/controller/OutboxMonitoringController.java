package com.titan.titancorebanking.controller;

import com.titan.titancorebanking.dto.response.OutboxStatusResponse;
import com.titan.titancorebanking.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/outbox")
@RequiredArgsConstructor
public class OutboxMonitoringController {
    
    private final OutboxRepository outboxRepository;
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    
    @GetMapping("/status")
    public Map<String, Object> getOutboxStatus() {
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
