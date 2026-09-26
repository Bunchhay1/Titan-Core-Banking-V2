package com.titan.promotions.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.titan.promotions.model.Campaign;
import com.titan.promotions.repository.CampaignRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class CampaignCacheService {
    
    private static final String CACHE_KEY = "campaigns:active";
    private static final long CACHE_TTL_MINUTES = 5;
    
    private final RedisTemplate<String, Object> redisTemplate;
    private final CampaignRepository campaignRepository;
    private final ObjectMapper objectMapper;
    
    public List<Campaign> getActiveCampaigns() {
        try {
            List<Object> cached = redisTemplate.opsForList().range(CACHE_KEY, 0, -1);
            if (cached != null && !cached.isEmpty()) {
                return cached.stream()
                    .map(obj -> objectMapper.convertValue(obj, Campaign.class))
                    .toList();
            }
        } catch (Exception e) {
            log.warn("Redis cache read failed, falling back to database", e);
        }
        
        return refreshCache();
    }
    
    public List<Campaign> refreshCache() {
        List<Campaign> campaigns = campaignRepository.findActiveCampaigns(LocalDateTime.now());
        
        try {
            redisTemplate.delete(CACHE_KEY);
            if (!campaigns.isEmpty()) {
                campaigns.forEach(c -> redisTemplate.opsForList().rightPush(CACHE_KEY, c));
                redisTemplate.expire(CACHE_KEY, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            }
            log.info("Cached {} active campaigns", campaigns.size());
        } catch (Exception e) {
            log.error("Failed to update campaign cache", e);
        }
        
        return campaigns;
    }
    
    @Scheduled(fixedRate = 60000)
    public void scheduledCacheRefresh() {
        refreshCache();
    }
    
    public void invalidateCache() {
        redisTemplate.delete(CACHE_KEY);
        log.info("Campaign cache invalidated");
    }
}
