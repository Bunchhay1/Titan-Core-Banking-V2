package com.titan.titancorebanking.batch;

import com.titan.titancorebanking.enums.AccountType;
import com.titan.titancorebanking.model.Account;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Slf4j
public class InterestProcessor implements ItemProcessor<Account, Account> {

    private static final BigDecimal SAVINGS_RATE = new BigDecimal("0.05"); // 5% APY
    private static final BigDecimal CHECKING_RATE = new BigDecimal("0.01"); // 1% APY
    private static final BigDecimal DAYS_IN_YEAR = new BigDecimal("365");

    @Nullable
    @Override
    public Account process(@NonNull final Account account) {

        // [MODIFIED] Added null-safety check for the balance to prevent NullPointerException during batch execution.
        // បន្ថែមការត្រួតពិនិត្យ Null ដើម្បីការពារកុំឲ្យកម្មវិធីគាំង (Crash) ពេលជួបគណនីដែលគ្មានទិន្នន័យសមតុល្យ (Balance = null)។
        if (account.getBalance() == null || account.getBalance().compareTo(BigDecimal.ZERO) <= 0) {
            return null; // Returning null instructs Spring Batch to cleanly skip this item
        }

        var dailyInterest = calculateDailyInterest(account.getBalance(), account.getAccountType());

        if (dailyInterest.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        /*
         * STAFF ENGINEER ARCHITECTURE NOTE:
         * Modifying the balance directly in this Spring Batch Processor updates the Account table,
         * but bypasses the Double-Entry Ledger and Transaction Audit tables. This creates "phantom money"
         * in a strictly compliant accounting system.
         *
         * FUTURE FIX: Modify this processor to return a custom DTO (e.g., `InterestCalculationResult`)
         * and implement a custom `ItemWriter` that saves the Account, Transaction, and LedgerEntries atomically.
         */
        account.setBalance(account.getBalance().add(dailyInterest));

        // [MODIFIED] Downgraded log level and refined message to reduce I/O bottleneck.
        log.debug("Interest computed for Account: {} | Amount: {}", account.getAccountNumber(), dailyInterest);

        return account;
    }

    // [MODIFIED] Extracted mathematical operations to enforce strict Banker's Rounding.
    // ញែកការគណនាហិរញ្ញវត្ថុទៅកាន់ Helper Method ព្រមទាំងប្តូរទៅប្រើ Banker's Rounding (HALF_EVEN) ដើម្បីកាត់បន្ថយភាពល្អៀងពេលគណនាលុយរាប់លានប្រតិបត្តិការ។
    private BigDecimal calculateDailyInterest(final BigDecimal balance, final AccountType type) {
        var annualRate = (type == AccountType.SAVINGS) ? SAVINGS_RATE : CHECKING_RATE;

        return balance.multiply(annualRate)
                .divide(DAYS_IN_YEAR, 4, RoundingMode.HALF_EVEN);
    }
}