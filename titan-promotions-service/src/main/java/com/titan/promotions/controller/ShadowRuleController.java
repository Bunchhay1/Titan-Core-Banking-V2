package com.titan.promotions.controller;

import com.titan.promotions.shadow.ShadowRuleEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/shadow")
@RequiredArgsConstructor
public class ShadowRuleController {
    private final ShadowRuleEngine shadowRuleEngine;
    
    @GetMapping("/rules/{ruleId}/cost")
    public ResponseEntity<Map<String, Object>> getProjectedCost(@PathVariable Long ruleId) {
        BigDecimal cost = shadowRuleEngine.getProjectedCost(ruleId);
        return ResponseEntity.ok(Map.of(
            "ruleId", ruleId,
            "projectedCost", cost,
            "status", "SHADOW_MODE"
        ));
    }
}
