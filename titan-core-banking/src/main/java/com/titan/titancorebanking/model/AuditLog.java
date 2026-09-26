package com.titan.titancorebanking.model;

import com.titan.titancorebanking.enums.AuditAction;
import com.titan.titancorebanking.enums.EmployeeRole;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "audit_logs", indexes = {
    @Index(name = "idx_audit_username", columnList = "username"),
    @Index(name = "idx_audit_timestamp", columnList = "timestamp")
})
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditAction action;
    
    @Column(nullable = false)
    private String username;

    @Enumerated(EnumType.STRING)
    private EmployeeRole employeeRole;

    @Column(nullable = false)
    private String ipAddress;
    
    @Column(nullable = false)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String details;
    
    @Column(nullable = false, updatable = false)
    private LocalDateTime timestamp;
    
    @PrePersist
    protected void onCreate() {
        if (timestamp == null) {
            timestamp = LocalDateTime.now();
        }
    }
}
