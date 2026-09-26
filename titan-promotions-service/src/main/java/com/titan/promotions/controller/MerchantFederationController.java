package com.titan.promotions.controller;

import com.titan.promotions.federation.MerchantCampaign;
import com.titan.promotions.federation.MerchantCampaignRepository;
import com.titan.promotions.escrow.EscrowClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/merchant")
@RequiredArgsConstructor
public class MerchantFederationController {
    private final MerchantCampaignRepository campaignRepository;
    private final EscrowClient escrowClient;
    
    @PostMapping("/campaigns")
    public ResponseEntity<MerchantCampaign> createCampaign(@RequestBody MerchantCampaign campaign) {
        campaign.setRemainingBudget(campaign.getTotalBudget());
        campaign.setActive(true);
        
        String escrowId = escrowClient.lockCampaignBudget(
            campaign.getId(), 
            campaign.getTotalBudget(), 
            "USD"
        );
        
        MerchantCampaign saved = campaignRepository.save(campaign);
        return ResponseEntity.ok(saved);
    }
    
    @GetMapping("/campaigns")
    public ResponseEntity<List<MerchantCampaign>> getCampaigns(@RequestParam String tenantId) {
        return ResponseEntity.ok(campaignRepository.findByTenantIdAndActiveTrue(tenantId));
    }
}
