package com.titan.titancorebanking.service;

import com.titan.titancorebanking.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxCleanupService {

    private final OutboxRepository outboxRepository;

    @Scheduled(cron = "0 0 2 * * *") // Daily at 2:00 AM
    @Transactional
    public void cleanupProcessedEvents() {
        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
        
        int deleted = outboxRepository.deleteByPublishedTrueAndCreatedAtBefore(cutoff);
        
        log.info("🧹 Outbox cleanup: {} events deleted (older than 7 days)", deleted);
    }
}
