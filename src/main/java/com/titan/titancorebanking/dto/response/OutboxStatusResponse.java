package com.titan.titancorebanking.dto.response;

import java.time.Instant;

/**
 * Immutable Outbox Status Response.
 * Used by the system administration endpoints to monitor Kafka relay health.
 */
public record OutboxStatusResponse(
        Long pendingEvents,
        Long publishedEvents,
        Long failedEvents,
        Instant oldestPendingEvent,
        Double avgPublishTimeSeconds,
        Boolean relayLocked,
        Instant timestamp
) {}