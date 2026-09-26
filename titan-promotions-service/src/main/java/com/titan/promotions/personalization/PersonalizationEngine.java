package com.titan.promotions.personalization;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
@RequiredArgsConstructor
public class PersonalizationEngine {
    
    private final ExpressionParser parser = new SpelExpressionParser();
    
    public CompletableFuture<BigDecimal> calculatePersonalizedReward(Long accountId, BigDecimal baseReward) {
        return CompletableFuture.supplyAsync(() -> {
            double propensityScore = fetchPropensityScore(accountId);
            
            StandardEvaluationContext context = new StandardEvaluationContext();
            context.setVariable("baseReward", baseReward);
            context.setVariable("propensityScore", propensityScore);
            
            // SpEL rule: Dynamic reward adjustment
            String rule = propensityScore > 0.8 
                ? "#baseReward * 4"  // High-value at-risk customer: $20
                : "#baseReward";     // Regular user: $5
            
            BigDecimal personalizedReward = parser.parseExpression(rule)
                .getValue(context, BigDecimal.class);
            
            log.info("Personalized reward for account {}: base={}, propensity={}, final={}", 
                accountId, baseReward, propensityScore, personalizedReward);
            
            return personalizedReward;
        });
    }
    
    private double fetchPropensityScore(Long accountId) {
        // Stub: Query AI model with transaction history
        // Returns churn risk score (0.0 = loyal, 1.0 = high churn risk)
        return Math.random();
    }
}
