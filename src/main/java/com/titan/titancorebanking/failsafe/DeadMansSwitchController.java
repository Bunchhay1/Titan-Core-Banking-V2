package com.titan.titancorebanking.failsafe;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/failsafe")
@RequiredArgsConstructor
public class DeadMansSwitchController {
    private final DeadMansSwitchService dmsService;

    public record HeartbeatRequest(String timestampEpochMs, String signature) {}
    public record TripRequest(String reason, String adminSignature) {}

    @PostMapping("/heartbeat")
    public ResponseEntity<Map<String, String>> heartbeat(@RequestBody HeartbeatRequest req) {
        try {
            dmsService.receiveHeartbeat(req.timestampEpochMs(), req.signature());
            return ResponseEntity.ok(Map.of("status", "HEARTBEAT_ACCEPTED"));
        } catch (Exception e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/trip")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> manualTrip(@RequestBody TripRequest req) {
        try {
            dmsService.manualTrip(req.reason(), req.adminSignature());
            return ResponseEntity.ok(Map.of("status", "LOCKDOWN_ACTIVATED"));
        } catch (Exception e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Boolean>> status() {
        return ResponseEntity.ok(Map.of("lockdown", dmsService.isLockdownActive()));
    }
}