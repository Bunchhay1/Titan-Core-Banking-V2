package com.titan.titancorebanking.service.fee;

import com.titan.titancorebanking.enums.UserTier;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;

@Component
public class PlatinumFeeStrategy implements FeeStrategy {

    private static final BigDecimal RATE = new BigDecimal("0.0025"); // 0.25% Fee

    @Override
    public BigDecimal calculateFee(BigDecimal amount) {
        return amount.multiply(RATE).setScale(4, java.math.RoundingMode.HALF_EVEN);
    }

    @Override
    public UserTier getTier() {
        return UserTier.PLATINUM;
    }
}
