package com.titan.promotions.shadow;

import com.titan.promotions.event.TransactionCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class ShadowRuleEngine {
    private final ShadowEvaluationRepository repository;
    private final ExpressionParser parser = new SpelExpressionParser();
    
    public void evaluateShadowRule(Long ruleId, String spelExpression, TransactionCompletedEvent event, BigDecimal rewardAmount) {
        StandardEvaluationContext context = new StandardEvaluationContext(event);
        context.setVariable("amount", event.getAmount());
        context.setVariable("currency", event.getCurrency());
        context.setVariable("type", event.getTransactionType());
        
        try {
            Boolean matched = parser.parseExpression(spelExpression).getValue(context, Boolean.class);
            
            ShadowEvaluation evaluation = ShadowEvaluation.builder()
                .ruleId(ruleId)
                .transactionId(parseTransactionId(event.getTransactionId()))
                .accountId(event.getAccountId() != null ? event.getAccountId() : 0L)
                .matched(matched != null && matched)
                .theoreticalPayout(matched != null && matched ? rewardAmount : BigDecimal.ZERO)
                .evaluatedAt(LocalDateTime.now())
                .ruleExpression(spelExpression)
                .build();
            
            repository.save(evaluation);
            log.debug("Shadow rule {} evaluated: matched={}, payout={}", ruleId, matched, rewardAmount);
        } catch (Exception e) {
            log.error("Shadow rule evaluation failed for rule {}", ruleId, e);
        }
    }
    
    public BigDecimal getProjectedCost(Long ruleId) {
        return repository.calculateTotalCost(ruleId);
    }

    private Long parseTransactionId(String transactionId) {
        if (transactionId == null || transactionId.isBlank()) return 0L;
        try {
            return Long.parseLong(transactionId.split("-")[0]);
        } catch (NumberFormatException e) {
            log.warn("Cannot parse transactionId '{}' as Long, using 0", transactionId);
            return 0L;
        }
    }
}
