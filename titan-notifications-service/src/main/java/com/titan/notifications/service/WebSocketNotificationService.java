package com.titan.notifications.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class WebSocketNotificationService {
    
    private final SimpMessagingTemplate messagingTemplate;
    
    public void pushToUser(String userId, String type, Map<String, Object> payload) {
        long start = System.nanoTime();
        messagingTemplate.convertAndSendToUser(userId, "/queue/alerts", Map.of(
            "type", type,
            "data", payload,
            "timestamp", System.currentTimeMillis()
        ));
        long latency = (System.nanoTime() - start) / 1_000_000;
        log.info("⚡ WebSocket push to user {} completed in {}ms", userId, latency);
    }
}
