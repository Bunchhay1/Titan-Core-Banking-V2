package com.titan.titancorebanking.controller;

import com.titan.titancorebanking.dto.response.OutboxStatusResponse;
import com.titan.titancorebanking.repository.OutboxRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * System Monitoring Controller.
 * Strictly limited to ADMIN roles. Provides observability into the transactional outbox.
 */
@RestController
@RequestMapping("/api/admin/outbox")
@RequiredArgsConstructor
@Tag(name = "System Administration", description = "Internal infrastructure monitoring")
public class OutboxMonitoringController {

    private final OutboxRepository outboxRepository;
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;

    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get Outbox Relay Status")
    public OutboxStatusResponse getOutboxStatus() {
        Long pending = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE published = FALSE AND retry_count < 5", Long.class);

        Long published = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE published = TRUE", Long.class);

        Long failed = outboxRepository.countFailedEvents(5);

        Instant oldest = jdbcTemplate.queryForObject(
                "SELECT MIN(created_at) FROM outbox_events WHERE published = FALSE", Instant.class);

        Double avgTime = jdbcTemplate.queryForObject(
                "SELECT AVG(EXTRACT(EPOCH FROM (published_at - created_at))) FROM outbox_events " +
                        "WHERE published = TRUE AND published_at > NOW() - INTERVAL '1 hour'",
                Double.class);

        Boolean isLocked = redisTemplate.hasKey("outbox:relay:lock");

        return new OutboxStatusResponse(
                pending != null ? pending : 0L,
                published != null ? published : 0L,
                failed != null ? failed : 0L,
                oldest,
                avgTime != null ? avgTime : 0.0,
                isLocked != null && isLocked,
                Instant.now()
        );
    }
}