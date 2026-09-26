package com.titan.titancorebanking.batch;

import com.titan.titancorebanking.enums.AccountType;
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

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerRepository ledgerRepository;
    private final UserRepository userRepository;

    /**
     * Daily interest calculation running at midnight off-peak hours (Asia/Phnom_Penh).
     * Processes eligible accounts in paginated batches with deterministic locking and balanced Double-Entry Ledger records.
     */
    @Scheduled(cron = "${banking.scheduler.interest-cron:0 0 0 * * ?}", zone = "Asia/Phnom_Penh")
    public void runInterestCalculationJob() {
        log.info("🚀 [BatchScheduler] Starting Daily Interest Calculation Batch...");
        long startTime = System.currentTimeMillis();
        int pageNumber = 0;
        int processedCount = 0;
        int interestAppliedCount = 0;

        try {
            // Ensure Bank's General Ledger Interest Expense Account is resolved
            Account glAccount = getOrCreateInterestExpenseGlAccount();

            Page<Account> accountPage;
            do {
                accountPage = accountRepository.findAll(
                        PageRequest.of(pageNumber, PAGE_SIZE, Sort.by("id").ascending())
                );

                for (Account account : accountPage.getContent()) {
                    processedCount++;
                    // Skip GL account itself or non-positive balances
                    if (account.getId().equals(glAccount.getId()) ||
                            account.getBalance() == null ||
                            account.getBalance().compareTo(BigDecimal.ZERO) <= 0) {
                        continue;
                    }

                    boolean applied = processInterestForAccount(account.getId(), glAccount.getId());
                    if (applied) {
                        interestAppliedCount++;
                    }
                }

                pageNumber++;
            } while (accountPage.hasNext());

            long duration = System.currentTimeMillis() - startTime;
            log.info("✅ [BatchScheduler] Interest Batch Finished in {} ms. Processed: {} accounts, Interest Applied: {}",
                    duration, processedCount, interestAppliedCount);

        } catch (Exception e) {
            log.error("❌ [BatchScheduler] Daily Interest Batch failed: ", e);
        }
    }

    /**
     * Processes individual account interest payout in an isolated transaction
     * with deterministic row locking (min(id) -> max(id)) and Double-Entry Ledger persistence.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean processInterestForAccount(Long customerAccountId, Long glAccountId) {
        try {
            // 1️⃣ Acquire deterministic pessimistic locks (min(id) -> max(id))
            Long firstId = Math.min(customerAccountId, glAccountId);
            Long secondId = Math.max(customerAccountId, glAccountId);

            Account firstLocked = accountRepository.findByIdWithLock(firstId)
                    .orElseThrow(() -> new IllegalArgumentException("Account not found: " + firstId));
            Account secondLocked = accountRepository.findByIdWithLock(secondId)
                    .orElseThrow(() -> new IllegalArgumentException("Account not found: " + secondId));

            Account glLocked = glAccountId.equals(firstLocked.getId()) ? firstLocked : secondLocked;
            Account customerLocked = customerAccountId.equals(firstLocked.getId()) ? firstLocked : secondLocked;

            if (customerLocked.getBalance().compareTo(BigDecimal.ZERO) <= 0) {
                return false;
            }

            // 2️⃣ Calculate interest using Bankers' Rounding (HALF_EVEN)
            BigDecimal apy = (customerLocked.getAccountType() == AccountType.SAVINGS) ? SAVINGS_APY : CHECKING_APY;
            BigDecimal dailyInterest = customerLocked.getBalance()
                    .multiply(apy)
                    .divide(DAYS_IN_YEAR, 2, RoundingMode.HALF_EVEN);

            if (dailyInterest.compareTo(BigDecimal.ZERO) <= 0) {
                return false;
            }

            // 3️⃣ Update balances
            glLocked.setBalance(glLocked.getBalance().subtract(dailyInterest));
            customerLocked.setBalance(customerLocked.getBalance().add(dailyInterest));
            accountRepository.save(glLocked);
            accountRepository.save(customerLocked);

            LocalDateTime now = LocalDateTime.now();

            // 4️⃣ Create Transaction Audit record
            Transaction tx = Transaction.builder()
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

            // 5️⃣ Persist Balanced Double-Entry Ledger:
            // 1) DEBIT Bank's Interest Expense GL Account
            LedgerEntry debitGlEntry = LedgerEntry.builder()
                    .transactionId(tx.getId())
                    .accountId(glLocked.getId())
                    .entryType(LedgerEntry.EntryType.DEBIT)
                    .amount(dailyInterest)
                    .entryDate(now)
                    .description("DEBIT Interest Expense GL Account")
                    .createdBy("SYSTEM_BATCH_INTEREST")
                    .build();

            // 2) CREDIT Customer's Deposit Account
            LedgerEntry creditCustomerEntry = LedgerEntry.builder()
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

            log.debug("💰 [Interest Applied] Account: {} | Payout: {} | Tx: {}",
                    customerLocked.getAccountNumber(), dailyInterest, tx.getTransactionReference());

            return true;
        } catch (Exception e) {
            log.error("Failed to apply interest for account ID {}: {}", customerAccountId, e.getMessage());
            return false;
        }
    }

    private Account getOrCreateInterestExpenseGlAccount() {
        return accountRepository.findByAccountNumber(GL_ACCOUNT_NUMBER)
                .orElseGet(() -> accountRepository.findById(1L).orElseGet(() -> {
                    User systemUser = userRepository.findAll().stream().findFirst().orElseGet(() -> {
                        User user = User.builder()
                                .username("system_treasury")
                                .password("System@123456")
                                .role("ROLE_ADMIN")
                                .build();
                        return userRepository.save(user);
                    });

                    Account gl = Account.builder()
                            .accountNumber(GL_ACCOUNT_NUMBER)
                            .accountType(AccountType.CHECKING)
                            .currency(com.titan.titancorebanking.enums.Currency.USD)
                            .balance(BigDecimal.ZERO)
                            .status(com.titan.titancorebanking.enums.AccountStatus.ACTIVE)
                            .user(systemUser)
                            .createdAt(LocalDateTime.now())
                            .build();
                    return accountRepository.save(gl);
                }));
    }
}