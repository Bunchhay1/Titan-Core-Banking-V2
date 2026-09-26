package com.titan.promotions.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * Configurable promotion rules with on/off toggles for digital banking members.
 */
@Configuration
@ConfigurationProperties(prefix = "promotion")
@Data
public class PromotionRuleProperties {

    private MemberBonusRule memberBonus = new MemberBonusRule();
    private CoinPointsRule coinPoints = new CoinPointsRule();

    @Data
    public static class MemberBonusRule {
        /** Enable/disable the $100 member bonus promotion. */
        private boolean enabled = true;
        /** Minimum deposit amount required to trigger the bonus. */
        private BigDecimal threshold = new BigDecimal("100.00");
        /** Flat bonus amount awarded when rule matches. */
        private BigDecimal bonusAmount = new BigDecimal("100.00");
        /** Transaction type that qualifies (e.g., DEPOSIT). */
        private String transactionType = "DEPOSIT";
        /** Channel metadata required (empty to ignore). */
        private String channel = "DIGITAL_BANKING";
    }

    @Data
    public static class CoinPointsRule {
        /** Enable/disable earning coin points. */
        private boolean enabled = true;
        /** Points earned per unit of currency. */
        private BigDecimal pointsPerCurrency = new BigDecimal("1.00");
        /** Cap to avoid runaway awards per transaction. */
        private BigDecimal maxPointsPerTransaction = new BigDecimal("10000");
        /** Transaction type that qualifies. */
        private String transactionType = "DEPOSIT";
        /** Channel metadata required (empty to ignore). */
        private String channel = "DIGITAL_BANKING";
    }
}
