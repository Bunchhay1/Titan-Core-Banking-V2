package com.titan.titancorebanking.batch;

import com.titan.titancorebanking.dto.internal.InterestCalculationResult; // Updated Import

import com.titan.titancorebanking.enums.AccountType;
import com.titan.titancorebanking.model.Account;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Slf4j
public class InterestProcessor implements ItemProcessor<Account, InterestCalculationResult> {
    private static final BigDecimal SAVINGS_RATE = new BigDecimal("0.05");
    private static final BigDecimal CHECKING_RATE = new BigDecimal("0.01");
    private static final BigDecimal DAYS_IN_YEAR = new BigDecimal("365");

    @Nullable
    @Override
    public InterestCalculationResult process(@NonNull final Account account) {
        if (account.getBalance() == null || account.getBalance().compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        var dailyInterest = calculateDailyInterest(account.getBalance(), account.getAccountType());
        if (dailyInterest.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        log.debug("Interest computed for Account: {} | Amount: {}", account.getAccountNumber(), dailyInterest);
        return new InterestCalculationResult(account, dailyInterest);
    }

    private BigDecimal calculateDailyInterest(final BigDecimal balance, final AccountType type) {
        var annualRate = (type == AccountType.SAVINGS) ? SAVINGS_RATE : CHECKING_RATE;
        return balance.multiply(annualRate)
                .divide(DAYS_IN_YEAR, 4, RoundingMode.HALF_EVEN);
    }
}