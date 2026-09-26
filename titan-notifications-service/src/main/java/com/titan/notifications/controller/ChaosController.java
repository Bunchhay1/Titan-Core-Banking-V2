package com.titan.notifications.controller;

import com.titan.notifications.chaos.ChaosSimulator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/chaos")
@RequiredArgsConstructor
public class ChaosController {
    
    private final ChaosSimulator chaosSimulator;
    
    @PostMapping("/blackout/start")
    public ResponseEntity<Map<String, String>> startBlackout() {
        chaosSimulator.startBlackout();
        return ResponseEntity.ok(Map.of("status", "blackout_started", "duration", "30_minutes"));
    }
    
    @PostMapping("/blackout/stop")
    public ResponseEntity<Map<String, String>> stopBlackout() {
        chaosSimulator.stopBlackout();
        return ResponseEntity.ok(Map.of("status", "blackout_stopped"));
    }
    
    @GetMapping("/blackout/status")
    public ResponseEntity<Map<String, Boolean>> getStatus() {
        return ResponseEntity.ok(Map.of("active", chaosSimulator.isBlackoutActive()));
    }
}
