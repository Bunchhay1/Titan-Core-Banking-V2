package com.titan.promotions.engine;

import com.titan.promotions.event.TransactionCompletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class RuleEngine {

    private final ExpressionParser parser = new SpelExpressionParser();

    /**
     * Evaluate a SpEL rule expression against a transaction event.
     *
     * SpEL variables available inside expressions:
     *   #transactionAmount  → event.getAmount()         (BigDecimal)
     *   #currency           → event.getCurrency()       (String)
     *   #transactionType    → event.getTransactionType() (String — maps to event.type)
     *   #accountId          → event.getAccountId()      (Long, may be null)
     *   #metadata           → event.getMetadata()       (Map<String,String>, may be null)
     *
     * Root object of the context is the event itself, so direct property access
     * like `amount >= 100` also works (without the # prefix).
     *
     * @param ruleExpression  SpEL boolean expression stored in campaigns.rule_expression
     * @param event           incoming Kafka transaction event
     * @return  true if the expression evaluates to true, false otherwise
     */
    public boolean evaluate(String ruleExpression, TransactionCompletedEvent event) {
        if (ruleExpression == null || ruleExpression.isBlank()) {
            log.warn("[RULE_ENGINE] Null or blank ruleExpression — defaulting to false");
            return false;
        }
        if (event == null) {
            log.warn("[RULE_ENGINE] Null event — defaulting to false");
            return false;
        }

        log.debug("[RULE_ENGINE] Evaluating rule='{}' | transactionId='{}' | type='{}' | amount='{}' | currency='{}'",
            ruleExpression,
            event.getTransactionId(),
            event.getTransactionType(),
            event.getAmount(),
            event.getCurrency());

        try {
            StandardEvaluationContext context = new StandardEvaluationContext(event);
            context.setVariable("transactionAmount", event.getAmount());
            context.setVariable("currency",          event.getCurrency());
            context.setVariable("transactionType",   event.getTransactionType());
            context.setVariable("accountId",         event.getAccountId());
            context.setVariable("metadata",          event.getMetadata());

            Boolean result = parser.parseExpression(ruleExpression).getValue(context, Boolean.class);
            boolean matched = result != null && result;

            log.debug("[RULE_ENGINE] Result={} for rule='{}' | transactionId='{}'",
                matched, ruleExpression, event.getTransactionId());

            return matched;

        } catch (Exception e) {
            log.error("[RULE_ENGINE] Rule evaluation failed | rule='{}' | transactionId='{}' | error={}",
                ruleExpression, event.getTransactionId(), e.getMessage(), e);
            return false;
        }
    }
}
