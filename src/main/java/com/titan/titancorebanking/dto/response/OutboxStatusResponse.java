package com.titan.titancorebanking.dto.response;

import java.time.Instant;

/**
 * Response for outbox monitoring endpoints
 */
public record OutboxStatusResponse(
    Long totalPending,
    Long totalPublished,
    Long totalFailed,
    Instant oldestPending,
    Double avgPublishTimeSeconds
) {}
