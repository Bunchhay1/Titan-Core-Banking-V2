package com.titan.notifications.service;

import com.titan.notifications.model.NotificationAudit;
import com.titan.notifications.repository.NotificationAuditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuditService {
    
    private final NotificationAuditRepository repository;
    
    public void logAttempt(String transactionId, String accountId, String channel, 
                          String recipient, String message, String provider, 
                          String locale, boolean urgent) {
        NotificationAudit audit = new NotificationAudit();
        audit.setTransactionId(transactionId);
        audit.setAccountId(accountId);
        audit.setChannel(channel);
        audit.setRecipient(recipient);
        audit.setMessage(message);
        audit.setProvider(provider);
        audit.setStatus("SENT");
        audit.setAttemptedAt(LocalDateTime.now());
        audit.setLocale(locale);
        audit.setUrgent(urgent);
        repository.save(audit);
    }
    
    public void logFailure(String transactionId, String accountId, String channel, 
                          String recipient, String error, String locale) {
        NotificationAudit audit = new NotificationAudit();
        audit.setTransactionId(transactionId);
        audit.setAccountId(accountId);
        audit.setChannel(channel);
        audit.setRecipient(recipient);
        audit.setMessage("");
        audit.setStatus("FAILED");
        audit.setErrorMessage(error);
        audit.setAttemptedAt(LocalDateTime.now());
        audit.setLocale(locale);
        audit.setUrgent(false);
        repository.save(audit);
    }
    
    public void logRateLimited(String transactionId, String accountId, String channel, String locale) {
        NotificationAudit audit = new NotificationAudit();
        audit.setTransactionId(transactionId);
        audit.setAccountId(accountId);
        audit.setChannel(channel);
        audit.setRecipient("");
        audit.setMessage("");
        audit.setStatus("RATE_LIMITED");
        audit.setAttemptedAt(LocalDateTime.now());
        audit.setLocale(locale);
        audit.setUrgent(false);
        repository.save(audit);
    }
    
    public void updateDeliveryStatus(String externalId, String status, LocalDateTime deliveredAt) {
        // Called by webhook controller
        log.info("📊 Delivery status update: {} → {}", externalId, status);
    }
}
