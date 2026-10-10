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
    
    /**
     * Fetch pending events with retry limit for batch processing
     */
    List<OutboxEvent> findTop100ByPublishedFalseAndRetryCountLessThanOrderByCreatedAtAsc(int maxRetries);
    
    /**
     * Count events stuck in retry loop (for monitoring)
     */
    @Query("SELECT COUNT(e) FROM OutboxEvent e WHERE e.published = false AND e.retryCount >= :maxRetries")
    Long countFailedEvents(@Param("maxRetries") int maxRetries);
    
    /**
     * Cleanup old published events (retention policy)
     */
    @Modifying
    @Query("DELETE FROM OutboxEvent o WHERE o.published = true AND o.createdAt < :cutoff")
    int deleteByPublishedTrueAndCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
