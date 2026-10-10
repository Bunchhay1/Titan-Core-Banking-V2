package com.titan.titancorebanking.batch;

import com.titan.titancorebanking.enums.TransactionStatus;
import com.titan.titancorebanking.enums.TransactionType;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.TransactionRepository;
import com.titan.titancorebanking.service.DoubleEntryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class InterestItemWriter implements ItemWriter<InterestCalculationResult> {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final DoubleEntryService doubleEntryService;

    private static final String GL_ACCOUNT_NUMBER = "GL-INTEREST-EXPENSE";
    private static final String SYSTEM_USER = "SYSTEM_BATCH";

    @Override
    @Transactional // Ensures the entire chunk is strictly ACID compliant
    public void write(Chunk<? extends InterestCalculationResult> chunk) throws Exception {
        if (chunk.isEmpty()) return;

        // [MODIFIED] PERFORMANCE OPTIMIZATION: Chunk-based GL Aggregation
        // ជំនួសឲ្យការ Lock គណនី GL រាប់រយដង ធ្វើឲ្យស្ទះប្រព័ន្ធ យើងបូកសរុបការប្រាក់ទាំងអស់ក្នុង Chunk នោះ
        // ហើយកាត់លុយពី GL តែម្ដងគត់ (O(1) Lock contention instead of O(N)).
        BigDecimal totalChunkInterest = chunk.getItems().stream()
                .map(InterestCalculationResult::interestAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Lock the GL Account precisely once per chunk
        Account glAccount = accountRepository.findByAccountNumberWithLock(GL_ACCOUNT_NUMBER)
                .orElseThrow(() -> new IllegalStateException("GL Interest Account missing!"));

        glAccount.setBalance(glAccount.getBalance().subtract(totalChunkInterest));
        accountRepository.save(glAccount);

        var transactions = new ArrayList<Transaction>();
        var customerAccounts = new ArrayList<Account>();
        var now = LocalDateTime.now();

        for (InterestCalculationResult result : chunk) {
            Account customerAccount = result.account();
            BigDecimal interest = result.interestAmount();

            customerAccount.setBalance(customerAccount.getBalance().add(interest));
            customerAccounts.add(customerAccount);

            // [MODIFIED] COMPLIANCE: Construct valid immutable Transaction records
            // កត់ត្រាប្រតិបត្តិការជាផ្លូវការចូលក្នុងប្រវត្តិគណនីរបស់អតិថិជន។
            Transaction tx = Transaction.builder()
                    .fromAccount(glAccount)
                    .toAccount(customerAccount)
                    .amount(interest)
                    .transactionType(TransactionType.INTEREST)
                    .status(TransactionStatus.SUCCESS)
                    .note("Daily Interest Payout")
                    .timestamp(now)
                    .transactionReference("INT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                    .build();
            transactions.add(tx);
        }

        // Execute batch inserts utilizing JDBC saveAll optimization
        accountRepository.saveAll(customerAccounts);
        var savedTransactions = transactionRepository.saveAll(transactions);

        // [MODIFIED] COMPLIANCE: 100% ACID Double-Entry Ledger Enforcements
        // ធានាថារាល់ការកាត់លុយ (Debit) និងបូកលុយ (Credit) ត្រូវបានកត់ត្រាចូល Ledger ដោយសុវត្ថិភាព។
        for (int i = 0; i < chunk.getItems().size(); i++) {
            InterestCalculationResult result = chunk.getItems().get(i);
            Transaction savedTx = savedTransactions.get(i);

            doubleEntryService.createDoubleEntry(
                    savedTx.getId(),
                    glAccount.getId(),
                    result.account().getId(),
                    result.interestAmount(),
                    "Daily Interest Payout",
                    SYSTEM_USER
            );
        }

        log.info("Batch processed {} accounts. Total chunk interest disbursed: ${}", chunk.size(), totalChunkInterest);
    }
}