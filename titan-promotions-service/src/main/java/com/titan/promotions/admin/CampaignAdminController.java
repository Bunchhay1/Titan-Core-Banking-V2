package com.titan.promotions.admin;

import com.titan.promotions.cache.CampaignCacheService;
import com.titan.promotions.model.Campaign;
import com.titan.promotions.repository.CampaignRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/admin/campaigns")
@Slf4j
@RequiredArgsConstructor
public class CampaignAdminController {
    
    private final CampaignRepository campaignRepository;
    private final CampaignCacheService cacheService;
    
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Campaign> createCampaign(@RequestBody CampaignRequest request) {
        Campaign campaign = Campaign.builder()
            .campaignCode(request.getCampaignCode())
            .name(request.getName())
            .ruleExpression(request.getRuleExpression())
            .rewardAmount(request.getRewardAmount())
            .status(Campaign.CampaignStatus.ACTIVE)
            .quotaLimit(request.getQuotaLimit())
            .quotaUsed(0)
            .startDate(request.getStartDate())
            .endDate(request.getEndDate())
            .createdAt(LocalDateTime.now())
            .build();
        
        Campaign saved = campaignRepository.save(campaign);
        cacheService.invalidateCache();
        
        log.info("Campaign created: {}", saved.getCampaignCode());
        return ResponseEntity.ok(saved);
    }
    
    @PutMapping("/{id}/pause")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> pauseCampaign(@PathVariable Long id) {
        Campaign campaign = campaignRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Campaign not found"));
        
        campaign.setStatus(Campaign.CampaignStatus.PAUSED);
        campaign.setUpdatedAt(LocalDateTime.now());
        campaignRepository.save(campaign);
        cacheService.invalidateCache();
        
        log.info("Campaign {} paused", campaign.getCampaignCode());
        return ResponseEntity.ok().build();
    }
    
    @PutMapping("/{id}/revoke")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> revokeCampaign(@PathVariable Long id) {
        Campaign campaign = campaignRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Campaign not found"));
        
        campaign.setStatus(Campaign.CampaignStatus.REVOKED);
        campaign.setUpdatedAt(LocalDateTime.now());
        campaignRepository.save(campaign);
        cacheService.invalidateCache();
        
        log.info("Campaign {} revoked", campaign.getCampaignCode());
        return ResponseEntity.ok().build();
    }
    
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Campaign> updateCampaign(@PathVariable Long id, @RequestBody CampaignRequest request) {
        Campaign campaign = campaignRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Campaign not found"));
        
        campaign.setName(request.getName());
        campaign.setRuleExpression(request.getRuleExpression());
        campaign.setRewardAmount(request.getRewardAmount());
        campaign.setQuotaLimit(request.getQuotaLimit());
        campaign.setStartDate(request.getStartDate());
        campaign.setEndDate(request.getEndDate());
        campaign.setUpdatedAt(LocalDateTime.now());
        
        Campaign updated = campaignRepository.save(campaign);
        cacheService.invalidateCache();
        
        log.info("Campaign {} updated", updated.getCampaignCode());
        return ResponseEntity.ok(updated);
    }
    
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<Campaign>> listCampaigns() {
        return ResponseEntity.ok(campaignRepository.findAll());
    }
}
