package com.titan.promotions.graphql;

import com.titan.promotions.model.Campaign;
import com.titan.promotions.repository.AppliedPromotionRepository;
import com.titan.promotions.repository.CampaignRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.*;
import org.springframework.stereotype.Controller;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class PromotionGraphQLController {

    private final AppliedPromotionRepository promotionRepository;
    private final CampaignRepository campaignRepository;

    @QueryMapping
    public List<PromotionAggregateDTO> promotionsByRegionAndStatus(
            @Argument String region, @Argument String status, @Argument Boolean unspent) {
        return promotionRepository.findAll().stream()
                .map(p -> new PromotionAggregateDTO(
                        p.getAccountId(), p.getPromotionType(),
                        p.getPromotionAmount().toPlainString(), p.getRewardStatus().name()))
                .toList();
    }

    @QueryMapping
    public List<Campaign> activeCampaigns() {
        return campaignRepository.findAll().stream()
                .filter(c -> c.getStatus() == Campaign.CampaignStatus.ACTIVE).toList();
    }

    @SchemaMapping(typeName = "User", field = "activeRewards")
    public List<PromotionAggregateDTO> activeRewards(UserReference user) {
        return promotionRepository.findByAccountId(Long.parseLong(user.id())).stream()
                .map(p -> new PromotionAggregateDTO(
                        p.getAccountId(), p.getPromotionType(),
                        p.getPromotionAmount().toPlainString(), p.getRewardStatus().name()))
                .toList();
    }

    public record UserReference(String id) {}

    public record PromotionAggregateDTO(
            Long accountId, String promotionType, String rewardAmount, String rewardStatus) {}
}
