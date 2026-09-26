package com.titan.titancorebanking.service.fee;

import com.titan.titancorebanking.enums.UserTier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class FeeStrategyFactory {

    private final Map<UserTier, FeeStrategy> strategies;

    // Auto-injects all implementations of FeeStrategy into a Map
    public FeeStrategyFactory(List<FeeStrategy> strategyList) {
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(FeeStrategy::getTier, Function.identity()));
    }

    public FeeStrategy getStrategy(UserTier tier) {
        return strategies.getOrDefault(tier, strategies.get(UserTier.STANDARD));
    }
}