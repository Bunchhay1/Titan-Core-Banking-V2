package com.titan.notifications.strategy;

import com.titan.notifications.chaos.ChaosSimulator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
@Slf4j
public class ProviderStrategyService {
    
    private final NotificationProvider primarySmsProvider;
    private final NotificationProvider secondarySmsProvider;
    private final NotificationProvider primaryEmailProvider;
    private final NotificationProvider secondaryEmailProvider;
    private final ChaosSimulator chaosSimulator;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    
    public ProviderStrategyService(
            @Qualifier("twilio") NotificationProvider twilioProvider,
            @Qualifier("sns") NotificationProvider snsProvider,
            @Qualifier("sendgrid") NotificationProvider sendgridProvider,
            @Qualifier("ses") NotificationProvider sesProvider,
            ChaosSimulator chaosSimulator,
            KafkaTemplate<String, Object> kafkaTemplate) {
        this.primarySmsProvider = twilioProvider;
        this.secondarySmsProvider = snsProvider;
        this.primaryEmailProvider = sendgridProvider;
        this.secondaryEmailProvider = sesProvider;
        this.chaosSimulator = chaosSimulator;
        this.kafkaTemplate = kafkaTemplate;
    }
    
    @Retryable(maxAttempts = 5, backoff = @Backoff(delay = 1000, multiplier = 2, maxDelay = 10000))
    public void sendSms(String recipient, String message) {
        try {
            chaosSimulator.simulateProviderFailure();
            primarySmsProvider.send(recipient, message);
        } catch (Exception e) {
            log.warn("⚠️ Primary SMS provider failed, switching to secondary: {}", e.getMessage());
            try {
                secondarySmsProvider.send(recipient, message);
            } catch (Exception e2) {
                log.error("❌ All SMS providers failed, sending to DLQ");
                sendToDlq("SMS", recipient, message, e2.getMessage());
                throw e2;
            }
        }
    }
    
    @Retryable(maxAttempts = 5, backoff = @Backoff(delay = 1000, multiplier = 2, maxDelay = 10000))
    public void sendEmail(String recipient, String message) {
        try {
            chaosSimulator.simulateProviderFailure();
            primaryEmailProvider.send(recipient, message);
        } catch (Exception e) {
            log.warn("⚠️ Primary Email provider failed, switching to secondary: {}", e.getMessage());
            try {
                secondaryEmailProvider.send(recipient, message);
            } catch (Exception e2) {
                log.error("❌ All Email providers failed, sending to DLQ");
                sendToDlq("EMAIL", recipient, message, e2.getMessage());
                throw e2;
            }
        }
    }
    
    private void sendToDlq(String channel, String recipient, String message, String error) {
        kafkaTemplate.send("banking.notifications.dlq", Map.of(
            "channel", channel,
            "recipient", recipient,
            "message", message,
            "error", error,
            "timestamp", System.currentTimeMillis()
        ));
    }
}

