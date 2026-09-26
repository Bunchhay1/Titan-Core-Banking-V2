package com.titan.notifications.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "notification_audit")
@Data
public class NotificationAudit {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String transactionId;
    
    @Column(nullable = false)
    private String accountId;
    
    @Column(nullable = false)
    private String channel; // SMS, EMAIL
    
    @Column(nullable = false)
    private String recipient;
    
    @Column(nullable = false, length = 2000)
    private String message;
    
    @Column(nullable = false)
    private String status; // SENT, FAILED, RATE_LIMITED, DELIVERED, BOUNCED
    
    private String provider; // twilio, sns, sendgrid, ses
    
    @Column(length = 1000)
    private String errorMessage;
    
    private String externalId; // Provider's message ID
    
    @Column(nullable = false)
    private LocalDateTime attemptedAt;
    
    private LocalDateTime deliveredAt;
    
    @Column(nullable = false)
    private String locale;
    
    @Column(nullable = false)
    private boolean urgent;
}
