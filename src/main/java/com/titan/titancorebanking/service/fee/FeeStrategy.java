package com.titan.titancorebanking.service.fee;

import com.titan.titancorebanking.enums.UserTier;
import java.math.BigDecimal;

public interface FeeStrategy {
    BigDecimal calculateFee(BigDecimal amount);
    UserTier getTier();
}