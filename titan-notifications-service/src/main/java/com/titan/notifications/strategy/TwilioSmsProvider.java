package com.titan.notifications.strategy;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

@Component("twilio")
@Slf4j
public class TwilioSmsProvider implements NotificationProvider {
    
    @Value("${notification.twilio.account-sid}")
    private String accountSid;
    
    @Value("${notification.twilio.from-number}")
    private String fromNumber;
    
    @Override
    @CircuitBreaker(name = "smsProvider", fallbackMethod = "sendFallback")
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public void send(String recipient, String message) {
        log.info("📱 Twilio SMS → {}: {}", recipient, message);
        // Actual Twilio API call would go here
    }
    
    public void sendFallback(String recipient, String message, Exception e) {
        log.error("❌ Twilio SMS failed, circuit breaker activated: {}", e.getMessage());
        throw new RuntimeException("SMS provider unavailable", e);
    }
    
    @Override
    public String getProviderName() {
        return "twilio";
    }
}
