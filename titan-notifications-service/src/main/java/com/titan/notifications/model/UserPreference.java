package com.titan.notifications.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "user_preferences")
@Data
public class UserPreference {
    
    @Id
    private String userId;
    
    @Column(nullable = false)
    private boolean marketingOptIn = true;
    
    @Column(nullable = false)
    private boolean transactionAlertsEnabled = true;
    
    @Column(nullable = false)
    private String preferredLocale = "en";
    
    @Column(nullable = false)
    private String smsNumber;
    
    @Column(nullable = false)
    private String email;
}
