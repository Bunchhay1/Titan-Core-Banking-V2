package com.titan.titancorebanking.service;

import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.LedgerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationService {

    private static final int CHUNK_SIZE = 1000;

    private final AccountRepository accountRepository;
    private final LedgerRepository ledgerRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Scheduled(cron = "0 0 3 * * *") // Daily at 3:00 AM
    @Transactional(readOnly = true)
    public void reconcileAllAccounts() {
        log.info("Starting daily ledger reconciliation across all accounts...");

        int page = 0;
        int discrepancies = 0;
        int processedAccounts = 0;
        Slice<Account> accountSlice;

        do {
            // Memory-safe chunked retrieval
            accountSlice = accountRepository.findAllAccountsForReconciliation(PageRequest.of(page, CHUNK_SIZE));

            for (Account account : accountSlice.getContent()) {
                processedAccounts++;
                BigDecimal ledgerBalance = ledgerRepository.calculateAccountBalance(account.getId());

                if (ledgerBalance == null) {
                    ledgerBalance = BigDecimal.ZERO;
                }

                if (account.getBalance().compareTo(ledgerBalance) != 0) {
                    discrepancies++;
                    log.error("RECONCILIATION FAILURE: Account {} | Expected Ledger: {} | Actual DB: {}",
                            account.getAccountNumber(), ledgerBalance, account.getBalance());

                    fireReconciliationAlert(account, ledgerBalance);
                }
            }
            page++;
        } while (accountSlice.hasNext());

        log.info("Reconciliation complete: {} accounts checked, {} discrepancies found",
                processedAccounts, discrepancies);
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