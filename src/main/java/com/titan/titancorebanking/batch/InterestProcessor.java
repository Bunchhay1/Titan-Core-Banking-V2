package com.titan.titancorebanking.batch;

import com.titan.titancorebanking.enums.AccountType;
import com.titan.titancorebanking.model.Account;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import java.math.BigDecimal;
import java.math.RoundingMode;

@Slf4j
public class InterestProcessor implements ItemProcessor<Account, Account> {
    
    private static final BigDecimal SAVINGS_RATE = new BigDecimal("0.05"); // 5% APY
    private static final BigDecimal CHECKING_RATE = new BigDecimal("0.01"); // 1% APY
    
    @Override
    public Account process(Account account) {
        if (account.getBalance().compareTo(BigDecimal.ZERO) <= 0) {
            return null; // Skip accounts with zero or negative balance
        }
        
        BigDecimal rate = account.getAccountType() == AccountType.SAVINGS ? SAVINGS_RATE : CHECKING_RATE;
        BigDecimal interest = account.getBalance()
            .multiply(rate)
            .divide(new BigDecimal("365"), 4, RoundingMode.HALF_UP); // Daily interest
        
        account.setBalance(account.getBalance().add(interest));
        
        log.debug("Interest applied: Account {} | Amount: {}", account.getAccountNumber(), interest);
        return account;
    }
}