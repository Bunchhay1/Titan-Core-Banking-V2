package com.titan.notifications.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true", matchIfMissing = false)
public class DlqConsumer {
    
    private final com.titan.notifications.strategy.ProviderStrategyService providerService;
    
    @KafkaListener(
        topics = "${spring.kafka.dlq.topic:banking.notifications.dlq}",
        groupId = "notification-dlq-recovery-group"
    )
    public void consumeDlq(Map<String, Object> message, Acknowledgment ack) {
        try {
            String channel = (String) message.get("channel");
            String recipient = (String) message.get("recipient");
            String body = (String) message.get("message");
            
            log.info("🔄 Retrying DLQ message: channel={}, recipient={}", channel, recipient);
            
            if ("SMS".equals(channel)) {
                providerService.sendSms(recipient, body);
            } else if ("EMAIL".equals(channel)) {
                providerService.sendEmail(recipient, body);
            }
            
            ack.acknowledge();
            log.info("✅ DLQ message recovered and delivered");
            
        } catch (Exception e) {
            log.error("❌ DLQ retry failed, will retry later: {}", e.getMessage());
            // Don't acknowledge - message will be retried
        }
    }
}
