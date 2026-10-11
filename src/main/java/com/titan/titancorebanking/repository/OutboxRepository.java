package com.titan.titancorebanking.repository;

import com.titan.titancorebanking.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    @Query(value = """
        SELECT * FROM outbox_events 
        WHERE published = false AND retry_count < :maxRetries 
        ORDER BY created_at ASC 
        LIMIT 100 
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<OutboxEvent> findPendingEventsForUpdate(@Param("maxRetries") int maxRetries);

    @Query("SELECT COUNT(o) FROM OutboxEvent o WHERE o.retryCount >= :maxRetries")
    Long countFailedEvents(@Param("maxRetries") int maxRetries);

    @Modifying
    int deleteByPublishedTrueAndCreatedAtBefore(Instant cutoff);
}