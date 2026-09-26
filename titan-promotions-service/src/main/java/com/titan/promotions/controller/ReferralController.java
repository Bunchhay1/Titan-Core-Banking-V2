package com.titan.promotions.controller;

import com.titan.promotions.graph.ReferralGraphService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/referrals")
@RequiredArgsConstructor
public class ReferralController {
    private final ReferralGraphService referralGraphService;
    
    @PostMapping("/add")
    public ResponseEntity<Map<String, String>> addReferral(
            @RequestParam Long referrerId, 
            @RequestParam Long referredAccountId) {
        referralGraphService.addReferral(referrerId, referredAccountId);
        return ResponseEntity.ok(Map.of("status", "success", "message", "Referral added to graph"));
    }
}
