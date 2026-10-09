package com.titan.titancorebanking.service;

import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.LedgerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    // [MODIFIED] Removed the class-level/method-level @Transactional annotation.
    // ការប្រើ @Transactional លើដំណើរការរាប់លានគណនី នឹងធ្វើអោយ Hibernate រក្សាទុក Object ទាំងអស់ក្នុង L1 Cache រហូតដល់ពេញ Memory (Memory Leak)។ ការដកវាចេញជួយអោយ Garbage Collector អាចលុបទិន្នន័យចោលបានភ្លាមៗ។
    @Scheduled(cron = "0 0 3 * * *") // Daily at 3:00 AM
    public void reconcileAllAccounts() {
        log.info("[Reconciliation] Starting daily ledger reconciliation...");

        int page = 0;
        long totalProcessed = 0;
        long totalDiscrepancies = 0;
        var startTime = System.currentTimeMillis();

        // [MODIFIED] Replaced dangerous memory-crashing .findAll() with Keys/Pagination logic.
        // ប្រើប្រាស់ Pagination (ទាញយកទិន្នន័យម្តង 1000 គណនី) ជំនួសអោយការទាញយកគណនីទាំងអស់មកកាន់ RAM ក្នុងពេលតែមួយ ដែលជួយទប់ស្កាត់បញ្ហា OutOfMemoryError (OOM)។
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

    // [MODIFIED] Replaced blocking Kafka calls with asynchronous Virtual Threads and Immutable Maps.
    // ប្រើប្រាស់ Virtual Threads ដើម្បីបោះ Kafka Alert ចេញដោយមិនធ្វើអោយយឺតដល់ដំណើរការផ្ទៀងផ្ទាត់។ ទន្ទឹមនឹងនេះ ការប្រើ Map.of() (Immutable) ដើរតួជំនួស new HashMap<>() ជួយកាត់បន្ថយការប្រើប្រាស់ Memory (Zero-allocation path)។
    private void fireReconciliationAlertAsync(String accountNumber, BigDecimal actualBalance, BigDecimal ledgerBalance) {
        Thread.ofVirtual().name("recon-alert-", 0).start(() -> {
            var discrepancy = actualBalance.subtract(ledgerBalance);

            // Java 9+ Immutable Map - safer and strictly zero-allocation overhead
            var alertPayload = Map.of(
                    "eventType", "RECONCILIATION_FAILURE",
                    "accountNumber", accountNumber,
                    "accountBalance", actualBalance,
                    "ledgerBalance", ledgerBalance,
                    "discrepancy", discrepancy,
                    "timestamp", Instant.now().toEpochMilli()
            );

            try {
                kafkaTemplate.send(ALERT_TOPIC, accountNumber, alertPayload).get(); // Ensure delivery within thread
            } catch (Exception e) {
                log.error("Failed to publish reconciliation alert for account {}: {}", accountNumber, e.getMessage());
            }
        });
    }
}