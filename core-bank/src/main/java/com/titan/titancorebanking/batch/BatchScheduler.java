package com.titan.titancorebanking.batch;

import com.titan.titancorebanking.enums.AccountStatus;
import com.titan.titancorebanking.enums.AccountType;
import com.titan.titancorebanking.enums.Currency;
import com.titan.titancorebanking.enums.TransactionStatus;
import com.titan.titancorebanking.enums.TransactionType;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.LedgerEntry;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.model.User;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.LedgerRepository;
import com.titan.titancorebanking.repository.TransactionRepository;
import com.titan.titancorebanking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.UUID;

@Component
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class BatchScheduler {

    private static final BigDecimal SAVINGS_APY = new BigDecimal("0.05");  // 5% APY
    private static final BigDecimal CHECKING_APY = new BigDecimal("0.01"); // 1% APY
    private static final BigDecimal DAYS_IN_YEAR = new BigDecimal("365");
    private static final int PAGE_SIZE = 500;
    private static final String GL_ACCOUNT_NUMBER = "GL-INTEREST-EXPENSE";
    private static final String SYSTEM_USER = "system_treasury";

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerRepository ledgerRepository;
    private final UserRepository userRepository;

    @Scheduled(cron = "${banking.scheduler.interest-cron:0 0 0 * * ?}", zone = "Asia/Phnom_Penh")
    public void runInterestCalculationJob() {
        log.info("[BatchScheduler] Starting Daily Interest Calculation Batch...");
        long startTime = System.currentTimeMillis();
        int pageNumber = 0;
        int processedCount = 0;
        int interestAppliedCount = 0;

        try {
            var glAccount = getOrCreateInterestExpenseGlAccount();
            Page<Account> accountPage;

            do {
                accountPage = accountRepository.findAll(PageRequest.of(pageNumber, PAGE_SIZE, Sort.by("id").ascending()));

                for (Account account : accountPage.getContent()) {
                    processedCount++;
                    // [MODIFIED] Extracted eligibility check to keep main loop clean.
                    // បំបែកលក្ខខណ្ឌឆែកគណនី (Eligibility) ដើម្បីអោយការ Loop មើលទៅស្រឡះ និងងាយយល់។
                    if (isEligibleForInterest(account, glAccount)) {
                        if (processInterestForAccount(account.getId(), glAccount.getId())) {
                            interestAppliedCount++;
                        }
                    }
                }
                pageNumber++;
            } while (accountPage.hasNext());

            long duration = System.currentTimeMillis() - startTime;
            log.info("[BatchScheduler] Interest Batch Finished in {} ms. Processed: {} accounts, Interest Applied: {}", duration, processedCount, interestAppliedCount);
        } catch (Exception e) {
            log.error("[BatchScheduler] Daily Interest Batch failed: ", e);
        }
    }

    private boolean isEligibleForInterest(Account account, Account glAccount) {
        return !account.getId().equals(glAccount.getId())
                && account.getBalance() != null
                && account.getBalance().compareTo(BigDecimal.ZERO) > 0;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean processInterestForAccount(Long customerAccountId, Long glAccountId) {
        try {
            // Deterministic locking to prevent deadlocks
            Long firstId = Math.min(customerAccountId, glAccountId);
            Long secondId = Math.max(customerAccountId, glAccountId);

            var firstLocked = accountRepository.findByIdWithLock(firstId).orElseThrow();
            var secondLocked = accountRepository.findByIdWithLock(secondId).orElseThrow();

            var glLocked = glAccountId.equals(firstLocked.getId()) ? firstLocked : secondLocked;
            var customerLocked = customerAccountId.equals(firstLocked.getId()) ? firstLocked : secondLocked;

            if (customerLocked.getBalance().compareTo(BigDecimal.ZERO) <= 0) return false;

            // [MODIFIED] Extracted math logic to a dedicated helper method.
            // ញែកការគណនាការប្រាក់ប្រចាំថ្ងៃ (Daily Interest Math) ទៅក្រៅ ជៀសវាងការសរសេរកូដគណនាផ្ទាល់ក្នុង Transaction។
            var dailyInterest = calculateDailyInterest(customerLocked);
            if (dailyInterest.compareTo(BigDecimal.ZERO) <= 0) return false;

            glLocked.setBalance(glLocked.getBalance().subtract(dailyInterest));
            customerLocked.setBalance(customerLocked.getBalance().add(dailyInterest));

            accountRepository.save(glLocked);
            accountRepository.save(customerLocked);

            // [MODIFIED] Grouped transaction and ledger creation into one focused method.
            // ប្រមូលផ្តុំការបង្កើត Transaction និង Double-Entry Ledger ទៅតែមួយកន្លែង (Encapsulation)។
            recordInterestTransactionAndLedger(glLocked, customerLocked, dailyInterest);

            log.debug("[Interest Applied] Account: {} | Payout: {}", customerLocked.getAccountNumber(), dailyInterest);
            return true;
        } catch (Exception e) {
            log.error("Failed to apply interest for account ID {}: {}", customerAccountId, e.getMessage());
            return false;
        }
    }

    private BigDecimal calculateDailyInterest(Account account) {
        var apy = account.getAccountType() == AccountType.SAVINGS ? SAVINGS_APY : CHECKING_APY;
        return account.getBalance().multiply(apy).divide(DAYS_IN_YEAR, 2, RoundingMode.HALF_EVEN);
    }

    private void recordInterestTransactionAndLedger(Account glLocked, Account customerLocked, BigDecimal dailyInterest) {
        var now = LocalDateTime.now();

        var tx = Transaction.builder()
                .fromAccount(glLocked)
                .toAccount(customerLocked)
                .amount(dailyInterest)
                .transactionType(TransactionType.INTEREST)
                .status(TransactionStatus.SUCCESS)
                .note("Daily Interest Payout (" + (customerLocked.getAccountType() != null ? customerLocked.getAccountType() : "SAVINGS") + ")")
                .timestamp(now)
                .transactionReference("INT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .build();
        transactionRepository.save(tx);

        var debitGlEntry = LedgerEntry.builder()
                .transactionId(tx.getId())
                .accountId(glLocked.getId())
                .entryType(LedgerEntry.EntryType.DEBIT)
                .amount(dailyInterest)
                .entryDate(now)
                .description("DEBIT Interest Expense GL Account")
                .createdBy("SYSTEM_BATCH_INTEREST")
                .build();

        var creditCustomerEntry = LedgerEntry.builder()
                .transactionId(tx.getId())
                .accountId(customerLocked.getId())
                .entryType(LedgerEntry.EntryType.CREDIT)
                .amount(dailyInterest)
                .entryDate(now)
                .description("CREDIT Daily Interest to Deposit Account")
                .createdBy("SYSTEM_BATCH_INTEREST")
                .build();

        ledgerRepository.save(debitGlEntry);
        ledgerRepository.save(creditCustomerEntry);
    }

    // [MODIFIED] Flattened the deeply nested orElseGet structures for readability.
    // កែសម្រួលការបង្កើតគណនី GL (General Ledger) ដោយលុបចោលការប្រើប្រាស់ orElseGet លាក់គ្នាជ្រៅៗ (Deep Nesting) ដែលពិបាកអាន។
    private Account getOrCreateInterestExpenseGlAccount() {
        return accountRepository.findByAccountNumber(GL_ACCOUNT_NUMBER)
                .orElseGet(this::createSystemGlAccount);
    }

    private Account createSystemGlAccount() {
        var systemUser = userRepository.findAll().stream().findFirst().orElseGet(() -> {
            var user = User.builder()
                    .username(SYSTEM_USER)
                    .password("System@123456")
                    .role("ROLE_ADMIN")
                    .build();
            return userRepository.save(user);
        });

        var gl = Account.builder()
                .accountNumber(GL_ACCOUNT_NUMBER)
                .accountType(AccountType.CHECKING)
                .currency(Currency.USD)
                .balance(BigDecimal.ZERO)
                .status(AccountStatus.ACTIVE)
                .user(systemUser)
                .createdAt(LocalDateTime.now())
                .build();
        return accountRepository.save(gl);
    }
}