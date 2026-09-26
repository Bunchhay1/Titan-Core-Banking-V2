package com.titan.notifications.controller;

import com.titan.notifications.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/webhooks")
@Slf4j
@RequiredArgsConstructor
public class WebhookController {
    
    private final AuditService auditService;
    
    @PostMapping("/twilio/status")
    public ResponseEntity<Void> twilioStatus(@RequestBody Map<String, String> payload) {
        String messageId = payload.get("MessageSid");
        String status = payload.get("MessageStatus");
        log.info("📥 Twilio webhook: {} → {}", messageId, status);
        auditService.updateDeliveryStatus(messageId, status, LocalDateTime.now());
        return ResponseEntity.ok().build();
    }
    
    @PostMapping("/sendgrid/events")
    public ResponseEntity<Void> sendgridEvents(@RequestBody Map<String, Object> payload) {
        String messageId = (String) payload.get("sg_message_id");
        String event = (String) payload.get("event");
        log.info("📥 SendGrid webhook: {} → {}", messageId, event);
        auditService.updateDeliveryStatus(messageId, event, LocalDateTime.now());
        return ResponseEntity.ok().build();
    }
}
