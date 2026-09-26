package com.titan.titancorebanking.service.fee;

import com.titan.titancorebanking.enums.UserTier;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;

@Component
public class StandardFeeStrategy implements FeeStrategy {

    private static final BigDecimal RATE = new BigDecimal("0.01"); // 1% Fee

    @Override
    public BigDecimal calculateFee(BigDecimal amount) {
        return amount.multiply(RATE);
    }

    @Override
    public UserTier getTier() {
        return UserTier.STANDARD;
    }
}