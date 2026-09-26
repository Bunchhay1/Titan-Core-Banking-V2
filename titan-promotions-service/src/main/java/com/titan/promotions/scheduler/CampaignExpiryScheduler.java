package com.titan.promotions.scheduler;

import com.titan.promotions.cache.CampaignCacheService;
import com.titan.promotions.model.Campaign;
import com.titan.promotions.repository.CampaignRepository;
import com.titan.promotions.service.DepositPromotionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class CampaignExpiryScheduler {
    
    private final CampaignRepository campaignRepository;
    private final CampaignCacheService cacheService;

    // ─────────────────────────────────────────────────────────────────────
    // General nightly sweep — runs at 02:00 AM every day
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Sweeps all campaigns whose endDate has passed and marks them COMPLETED.
     * This covers any campaign in the system, including DEPOSIT_BONUS_JUL_AUG_2026.
     *
     * Cron: "0 0 2 * * ?" → every day at 02:00 AM
     */
    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional("transactionManager")
    public void sweepExpiredCampaigns() {
        log.info("[SCHEDULER] Starting campaign expiry sweep");
        
        LocalDateTime now = LocalDateTime.now();
        List<Campaign> expired = campaignRepository.findExpiredCampaigns(now);
        
        expired.forEach(campaign -> {
            campaign.setStatus(Campaign.CampaignStatus.COMPLETED);
            campaign.setUpdatedAt(now);
            campaignRepository.save(campaign);
            log.info("[SCHEDULER] Campaign '{}' marked as COMPLETED (endDate={})",
                    campaign.getCampaignCode(), campaign.getEndDate());
        });
        
        if (!expired.isEmpty()) {
            cacheService.invalidateCache();
            log.info("[SCHEDULER] Closed {} expired campaign(s) and cleared cache", expired.size());
        } else {
            log.debug("[SCHEDULER] No expired campaigns found");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Deposit Bonus campaign — precise check every minute after campaign end
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Dedicated check for the DEPOSIT_BONUS_JUL_AUG_2026 campaign.
     *
     * Runs every minute during Aug 23–24 2026 to close the promotion as close
     * to 2026-08-23T23:59:59 as possible, without waiting for the 2 AM sweep.
     *
     * Cron: "0 * * * * ?" → every minute (runs throughout the day; no-op until
     *        the campaign end date is reached, so overhead is negligible).
     */
    @Scheduled(cron = "0 * * * * ?")
    @Transactional("transactionManager")
    public void closeDepositBonusCampaignOnExpiry() {
        LocalDateTime now = LocalDateTime.now();

        // Only act after campaign end date has passed
        if (!now.isAfter(DepositPromotionService.CAMPAIGN_END)) {
            return; // still within window — nothing to do
        }

        Optional<Campaign> opt = campaignRepository
                .findByCampaignCode(DepositPromotionService.CAMPAIGN_CODE);

        if (opt.isEmpty()) {
            return; // campaign not seeded yet
        }

        Campaign campaign = opt.get();
        if (campaign.getStatus() == Campaign.CampaignStatus.COMPLETED
                || campaign.getStatus() == Campaign.CampaignStatus.REVOKED) {
            return; // already closed
        }

        // Mark as COMPLETED
        campaign.setStatus(Campaign.CampaignStatus.COMPLETED);
        campaign.setUpdatedAt(now);
        campaignRepository.save(campaign);
        cacheService.invalidateCache();

        log.info("[SCHEDULER] 🔒 Deposit Bonus campaign '{}' CLOSED at {} (endDate was {})",
                DepositPromotionService.CAMPAIGN_CODE, now, DepositPromotionService.CAMPAIGN_END);
    }
}
