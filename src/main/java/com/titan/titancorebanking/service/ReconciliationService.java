package com.titan.titancorebanking.service;

import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.LedgerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationService {

    private final AccountRepository accountRepository;
    private final LedgerRepository ledgerRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final int PAGE_SIZE = 1000;

    /**
     * Daily at 3:00 AM.
     * Removed the global @Transactional to prevent massive long-running MVCC snapshots.
     * Implemented keyset pagination to prevent Hibernate L1 Cache OOM explosions.
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void reconcileAllAccounts() {
        log.info("  Starting daily ledger reconciliation...");

        int discrepancies = 0;
        int processedCount = 0;
        Long lastProcessedId = 0L;

        Page<Account> accountPage;
        do {
            accountPage = accountRepository.findByIdGreaterThanOrderByIdAsc(
                    lastProcessedId,
                    PageRequest.of(0, PAGE_SIZE)
            );

            for (Account account : accountPage.getContent()) {
                lastProcessedId = account.getId();
                processedCount++;

                boolean isBalanced = checkAccountBalance(account);
                if (!isBalanced) {
                    discrepancies++;
                }
            }
        } while (accountPage.hasNext());

        log.info("  Reconciliation complete: {} accounts checked, {} discrepancies found",
                processedCount, discrepancies);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    protected boolean checkAccountBalance(Account account) {
        BigDecimal ledgerBalance = ledgerRepository.calculateAccountBalance(account.getId());

        if (ledgerBalance == null) {
            ledgerBalance = BigDecimal.ZERO;
        }

        if (account.getBalance().compareTo(ledgerBalance) != 0) {
            log.error("  RECONCILIATION FAILURE: Account {} | Expected (Ledger): {} | Actual (Table): {}",
                    account.getAccountNumber(), ledgerBalance, account.getBalance());

            fireReconciliationAlert(account, ledgerBalance);
            return false;
        }
        return true;
    }

    private void fireReconciliationAlert(Account account, BigDecimal ledgerBalance) {
        Map<String, Object> alert = new HashMap<>();
        alert.put("eventType", "RECONCILIATION_FAILURE");
        alert.put("accountNumber", account.getAccountNumber());
        alert.put("accountBalance", account.getBalance());
        alert.put("ledgerBalance", ledgerBalance);
        alert.put("discrepancy", account.getBalance().subtract(ledgerBalance));
        alert.put("timestamp", System.currentTimeMillis());

        kafkaTemplate.send("banking.alerts", account.getAccountNumber(), alert);
    }
}