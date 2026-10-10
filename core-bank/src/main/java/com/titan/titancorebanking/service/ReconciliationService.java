package com.titan.titancorebanking.service;

import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.LedgerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationService {
    private static final int BATCH_SIZE = 1000;
    private static final String ALERT_TOPIC = "banking.alerts";

    private final AccountRepository accountRepository;
    private final LedgerRepository ledgerRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Scheduled(cron = "0 0 3 * * *") // Daily at 3:00 AM
    public void reconcileAllAccounts() {
        log.info("[Reconciliation] Starting daily ledger reconciliation...");
        int page = 0;
        long totalProcessed = 0;
        long totalDiscrepancies = 0;
        var startTime = System.currentTimeMillis();

        while (true) {
            var accountPage = accountRepository.findAll(PageRequest.of(page, BATCH_SIZE));
            if (accountPage.isEmpty()) {
                break;
            }

            for (var account : accountPage.getContent()) {
                totalProcessed++;
                if (!verifyAccountBalance(account)) {
                    totalDiscrepancies++;
                }
            }
            page++;
        }

        var duration = System.currentTimeMillis() - startTime;
        log.info("[Reconciliation] Complete. Processed: {} | Discrepancies: {} | Duration: {}ms",
                totalProcessed, totalDiscrepancies, duration);
    }

    private boolean verifyAccountBalance(Account account) {
        var expectedBalance = ledgerRepository.calculateAccountBalance(account.getId());
        var actualBalance = account.getBalance();
        var ledgerBalance = expectedBalance != null ? expectedBalance : BigDecimal.ZERO;

        if (actualBalance.compareTo(ledgerBalance) != 0) {
            log.error("RECONCILIATION FAILURE: Account {} | Expected (Ledger): {} | Actual (Account): {}",
                    account.getAccountNumber(), ledgerBalance, actualBalance);
            fireReconciliationAlertAsync(account.getAccountNumber(), actualBalance, ledgerBalance);
            return false;
        }
        return true;
    }

    private void fireReconciliationAlertAsync(String accountNumber, BigDecimal actualBalance, BigDecimal ledgerBalance) {
        // Capture parent thread's MDC context
        final Map<String, String> contextMap = MDC.getCopyOfContextMap();

        Thread.ofVirtual().name("recon-alert-", 0).start(() -> {
            // Propagate MDC to the Virtual Thread
            if (contextMap != null) {
                MDC.setContextMap(contextMap);
            }

            try {
                var discrepancy = actualBalance.subtract(ledgerBalance);
                var alertPayload = Map.of(
                        "eventType", "RECONCILIATION_FAILURE",
                        "accountNumber", accountNumber,
                        "accountBalance", actualBalance,
                        "ledgerBalance", ledgerBalance,
                        "discrepancy", discrepancy,
                        "timestamp", Instant.now().toEpochMilli()
                );

                kafkaTemplate.send(ALERT_TOPIC, accountNumber, alertPayload).get();
            } catch (Exception e) {
                log.error("Failed to publish reconciliation alert for account {}: {}", accountNumber, e.getMessage());
            } finally {
                MDC.clear(); // Prevent memory leaks
            }
        });
    }
}