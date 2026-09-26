package com.titan.titancorebanking.repository;

import com.titan.titancorebanking.model.DomainEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EventStoreRepository extends JpaRepository<DomainEvent, Long> {
    
    List<DomainEvent> findByAggregateIdOrderByVersionAsc(UUID aggregateId);
    
    List<DomainEvent> findByEventType(String eventType);
    
    List<DomainEvent> findByCorrelationId(String correlationId);
    
    @Query("SELECT MAX(e.version) FROM DomainEvent e WHERE e.aggregateId = :aggregateId")
    Long findLatestVersion(@Param("aggregateId") UUID aggregateId);
}
