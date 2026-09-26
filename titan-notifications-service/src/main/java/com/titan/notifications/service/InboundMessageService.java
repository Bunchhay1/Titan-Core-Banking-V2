package com.titan.notifications.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class InboundMessageService {
    
    private final KafkaTemplate<String, Object> kafkaTemplate;
    
    public void processInboundSms(String from, String body) {
        String normalized = body.trim().toUpperCase();
        
        if (normalized.equals("BLOCK") || normalized.equals("LOCK")) {
            log.warn("🚨 EMERGENCY: User {} requested account lock via SMS", from);
            kafkaTemplate.send("banking.security.emergency", Map.of(
                "action", "LOCK_ACCOUNT",
                "phoneNumber", from,
                "reason", "USER_SMS_REQUEST",
                "timestamp", System.currentTimeMillis()
            ));
        } else if (normalized.equals("CONFIRM") || normalized.equals("YES")) {
            log.info("✅ User {} confirmed transaction via SMS", from);
            kafkaTemplate.send("banking.transactions.confirmed", Map.of(
                "phoneNumber", from,
                "confirmation", true,
                "timestamp", System.currentTimeMillis()
            ));
        } else {
            log.info("📝 Unrecognized command from {}: {}", from, body);
        }
    }
}
