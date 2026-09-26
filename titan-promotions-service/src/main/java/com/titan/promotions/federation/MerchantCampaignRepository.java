package com.titan.promotions.federation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface MerchantCampaignRepository extends JpaRepository<MerchantCampaign, Long> {
    List<MerchantCampaign> findByTenantIdAndActiveTrue(String tenantId);
    
    @Modifying
    @Query("UPDATE MerchantCampaign m SET m.remainingBudget = m.remainingBudget - :amount WHERE m.id = :campaignId AND m.remainingBudget >= :amount")
    int deductBudget(Long campaignId, BigDecimal amount);
}
