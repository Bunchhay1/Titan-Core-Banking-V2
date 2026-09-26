package com.titan.promotions.eventsourcing;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "rule_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleEvent {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private Long ruleId;
    
    @Column(nullable = false)
    private String eventType; // CREATED, UPDATED, DISABLED
    
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload; // JSON snapshot
    
    @Column(nullable = false)
    private String changedBy;
    
    @Column(nullable = false)
    private LocalDateTime occurredAt;
}
