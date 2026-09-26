package com.titan.promotions.repository;

import com.titan.promotions.model.Campaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CampaignRepository extends JpaRepository<Campaign, Long> {
    
    @Query("SELECT c FROM Campaign c WHERE c.status = 'ACTIVE' AND c.startDate <= :now AND c.endDate >= :now")
    List<Campaign> findActiveCampaigns(LocalDateTime now);
    
    Optional<Campaign> findByCampaignCode(String campaignCode);
    
    @Query("SELECT c FROM Campaign c WHERE c.status = 'ACTIVE' AND c.endDate < :now")
    List<Campaign> findExpiredCampaigns(LocalDateTime now);
}
