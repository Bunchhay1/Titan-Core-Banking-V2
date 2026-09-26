package com.titan.notifications.controller;

import com.titan.notifications.service.InboundMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/webhooks")
@Slf4j
@RequiredArgsConstructor
public class InboundWebhookController {
    
    private final InboundMessageService inboundService;
    
    @PostMapping("/twilio/sms")
    public ResponseEntity<String> handleTwilioSms(
            @RequestParam("From") String from,
            @RequestParam("Body") String body,
            @RequestParam(value = "AccountSid", required = false) String accountSid) {
        
        log.info("📥 Twilio SMS webhook: from={}, body={}", from, body);
        inboundService.processInboundSms(from, body);
        return ResponseEntity.ok("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response></Response>");
    }
    
    @PostMapping("/whatsapp")
    public ResponseEntity<Map<String, String>> handleWhatsApp(@RequestBody Map<String, Object> payload) {
        String from = (String) payload.get("from");
        String body = (String) payload.get("text");
        
        log.info("📥 WhatsApp webhook: from={}, body={}", from, body);
        inboundService.processInboundSms(from, body);
        return ResponseEntity.ok(Map.of("status", "received"));
    }
}
