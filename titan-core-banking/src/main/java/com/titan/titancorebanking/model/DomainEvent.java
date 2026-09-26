package com.titan.titancorebanking.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "event_store", indexes = {
    @Index(name = "idx_event_aggregate", columnList = "aggregateId, aggregateType, version"),
    @Index(name = "idx_event_timestamp", columnList = "timestamp"),
    @Index(name = "idx_event_type", columnList = "eventType")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DomainEvent {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, unique = true)
    private UUID eventId;
    
    @Column(nullable = false)
    private UUID aggregateId;
    
    @Column(nullable = false, length = 100)
    private String aggregateType;
    
    @Column(nullable = false, length = 100)
    private String eventType;
    
    @Column(nullable = false, columnDefinition = "TEXT")
    private String eventData;
    
    @Column(nullable = false)
    private Long version;
    
    @Column(nullable = false)
    private LocalDateTime timestamp;
    
    @Column(nullable = false, length = 100)
    private String userId;
    
    @Column(length = 100)
    private String correlationId;
    
    @Column(columnDefinition = "TEXT")
    private String metadata;
    
    @PrePersist
    protected void onCreate() {
        if (eventId == null) {
            eventId = UUID.randomUUID();
        }
        if (timestamp == null) {
            timestamp = LocalDateTime.now();
        }
    }
}
