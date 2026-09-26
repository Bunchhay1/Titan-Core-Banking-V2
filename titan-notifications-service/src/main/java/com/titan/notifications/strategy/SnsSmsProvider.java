package com.titan.notifications.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

@Component("sns")
@Slf4j
public class SnsSmsProvider implements NotificationProvider {
    
    @Override
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public void send(String recipient, String message) {
        log.info("📱 AWS SNS SMS → {}: {}", recipient, message);
        // AWS SNS API call would go here
    }
    
    @Override
    public String getProviderName() {
        return "sns";
    }
}
