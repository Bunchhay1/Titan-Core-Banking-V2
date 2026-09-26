package com.titan.notifications.strategy;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

@Component("sendgrid")
@Slf4j
public class SendGridEmailProvider implements NotificationProvider {
    
    @Value("${notification.sendgrid.api-key}")
    private String apiKey;
    
    @Override
    @CircuitBreaker(name = "emailProvider", fallbackMethod = "sendFallback")
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public void send(String recipient, String message) {
        log.info("📧 SendGrid Email → {}", recipient);
        // SendGrid API call would go here
    }
    
    public void sendFallback(String recipient, String message, Exception e) {
        log.error("❌ SendGrid failed, circuit breaker activated: {}", e.getMessage());
        throw new RuntimeException("Email provider unavailable", e);
    }
    
    @Override
    public String getProviderName() {
        return "sendgrid";
    }
}
