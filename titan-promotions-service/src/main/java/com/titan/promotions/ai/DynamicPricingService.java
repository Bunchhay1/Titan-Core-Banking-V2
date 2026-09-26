package com.titan.promotions.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Map;

@Service
@Slf4j
public class DynamicPricingService {
    private final WebClient aiClient;
    
    public DynamicPricingService(@Value("${titan.ai-service.url:http://localhost:8085}") String aiServiceUrl) {
        this.aiClient = WebClient.builder().baseUrl(aiServiceUrl).build();
    }
    
    public BigDecimal getOptimalReward(Long campaignId, Long accountId, BigDecimal baseReward) {
        try {
            Map<String, Object> request = Map.of(
                "campaign_id", campaignId,
                "account_id", accountId,
                "base_reward", baseReward.toString(),
                "action", "optimize_reward"
            );
            
            Map<String, Object> response = aiClient.post()
                .uri("/api/optimize-reward")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .block();
            
            if (response != null && response.containsKey("optimal_reward")) {
                BigDecimal optimal = new BigDecimal(response.get("optimal_reward").toString());
                log.info("AI optimized reward from {} to {} for campaign {}", baseReward, optimal, campaignId);
                return optimal;
            }
        } catch (Exception e) {
            log.warn("AI service unavailable, using base reward: {}", e.getMessage());
        }
        
        return baseReward;
    }
}
