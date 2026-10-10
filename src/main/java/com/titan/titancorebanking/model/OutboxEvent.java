package com.titan.titancorebanking.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "outbox_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String aggregateId;
    
    @Column(nullable = false)
    private String aggregateType;
    
    @Column(nullable = false)
    private String eventType;
    
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;
    
    @Column(nullable = false)
    @Builder.Default
    private Boolean published = false;
    
    @Column(nullable = false)
    @Builder.Default
    private Integer retryCount = 0;
    
    private Instant lastRetryAt;
    
    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
    
    private Instant publishedAt;
    
    private String lastError;
}
