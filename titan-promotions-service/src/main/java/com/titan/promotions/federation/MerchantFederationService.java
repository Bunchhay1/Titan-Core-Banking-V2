package com.titan.promotions.federation;

import com.titan.promotions.escrow.EscrowClient;
import com.titan.promotions.event.TransactionCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class MerchantFederationService {
    private final MerchantCampaignRepository campaignRepository;
    private final EscrowClient escrowClient;
    
    @Transactional
    public void evaluateMerchantCampaigns(TransactionCompletedEvent event, String tenantId) {
        List<MerchantCampaign> campaigns = campaignRepository.findByTenantIdAndActiveTrue(tenantId);
        
        for (MerchantCampaign campaign : campaigns) {
            if (campaign.getRemainingBudget().compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("Campaign {} exhausted budget", campaign.getId());
                continue;
            }
            
            BigDecimal reward = calculateReward(event, campaign);
            if (reward.compareTo(BigDecimal.ZERO) > 0) {
                int updated = campaignRepository.deductBudget(campaign.getId(), reward);
                if (updated > 0) {
                    escrowClient.releaseFunds(
                        "MERCHANT-" + campaign.getId(), 
                        event.getAccountId(), 
                        reward
                    );
                    log.info("Merchant {} paid {} to account {} from campaign {}", 
                        campaign.getMerchantName(), reward, event.getAccountId(), campaign.getId());
                }
            }
        }
    }
    
    private BigDecimal calculateReward(TransactionCompletedEvent event, MerchantCampaign campaign) {
        // Simplified: 2% of transaction amount
        return event.getAmount().multiply(new BigDecimal("0.02"));
    }
}
