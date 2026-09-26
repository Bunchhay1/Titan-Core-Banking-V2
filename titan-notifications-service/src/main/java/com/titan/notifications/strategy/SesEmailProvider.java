package com.titan.notifications.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

@Component("ses")
@Slf4j
public class SesEmailProvider implements NotificationProvider {
    
    @Override
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public void send(String recipient, String message) {
        log.info("📧 AWS SES Email → {}", recipient);
        // AWS SES API call would go here
    }
    
    @Override
    public String getProviderName() {
        return "ses";
    }
}
