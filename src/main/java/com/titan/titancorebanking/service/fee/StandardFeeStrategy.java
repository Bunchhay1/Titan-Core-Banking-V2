package com.titan.titancorebanking.service.fee;

import com.titan.titancorebanking.enums.UserTier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Standard tier (default) fee strategy — 1% of the transfer amount.
 *
 * <h3>Monetary precision contract</h3>
 * Fee is computed as {@code amount × 0.01}, rounded to 4 decimal places using
 * {@link RoundingMode#HALF_EVEN} (banker's rounding). Scale-4 matches the account
 * balance column precision ({@code NUMERIC(30,4)}) so no rounding difference is
 * silently discarded when the fee is added to {@code totalDeduction}.
 *
 * <p>Example: amount = $100.005 → fee = $1.0001 (rounded HALF_EVEN from 1.00005).
 * Do not reduce to scale-2 here; the caller ({@link com.titan.titancorebanking.service.TransactionService})
 * accumulates fee into {@code totalDeduction} before any balance write, so sub-cent
 * precision is preserved through the full deduction path.
 */
@Component
public class StandardFeeStrategy implements FeeStrategy {

    private static final BigDecimal RATE = new BigDecimal("0.01"); // 1%

    /**
     * Calculates the fee for the given transfer amount.
     *
     * @param amount the base transfer amount (must be non-null, positive, scale ≤ 4)
     * @return fee amount at scale 4, rounded HALF_EVEN — never negative, never null
     */
    @Override
    public BigDecimal calculateFee(BigDecimal amount) {
        // HALF_EVEN (banker's rounding) is the standard for financial calculations —
        // it eliminates statistical bias introduced by always rounding 0.5 up.
        return amount.multiply(RATE).setScale(4, RoundingMode.HALF_EVEN);
    }

    @Override
    public UserTier getTier() {
        return UserTier.STANDARD;
    }
}
