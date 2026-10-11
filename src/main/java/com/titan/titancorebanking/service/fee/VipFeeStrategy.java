package com.titan.titancorebanking.service.fee;

import com.titan.titancorebanking.enums.UserTier;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;

@Component
public class VipFeeStrategy implements FeeStrategy {

    @Override
    public BigDecimal calculateFee(BigDecimal amount) {
        return BigDecimal.ZERO.setScale(4, java.math.RoundingMode.HALF_EVEN); // Free for VIPs!
    }

    @Override
    public UserTier getTier() {
        return UserTier.VIP;
    }
}