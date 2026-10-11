package com.titan.titancorebanking.service;

import com.titan.titancorebanking.dto.request.TransactionRequest;
import com.titan.titancorebanking.enums.ScheduleStatus;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.ScheduledTransaction;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.ScheduledTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Hardened Scheduled Transaction Service.
 * Rectifies the empty execution block from the legacy implementation.
 * Processes recurring transfers (e.g., rent, subscriptions) using strict transactional boundaries.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScheduledTransactionService {

    private final ScheduledTransactionRepository scheduledTransactionRepository;
    private final TransactionService transactionService;
    private final AccountRepository accountRepository;

    @Scheduled(cron = "0 * * * * *") // Runs every minute
    public void processScheduledTransactions() {
        LocalDateTime now = LocalDateTime.now();
        List<ScheduledTransaction> dueTransactions = scheduledTransactionRepository
                .findByStatusAndScheduledDateLessThanEqual(ScheduleStatus.PENDING.name(), now);

        if (!dueTransactions.isEmpty()) {
            log.info("Processing {} due scheduled transactions...", dueTransactions.size());
            for (ScheduledTransaction st : dueTransactions) {
                try {
                    executeSingleScheduleSafe(st, now);
                } catch (Exception e) {
                    log.error("Failed to execute scheduled transaction ID {}: {}", st.getId(), e.getMessage());
                }
            }
        }
    }

    /**
     * Isolated transaction boundary prevents a single NSF (Non-Sufficient Funds)
     * failure from rolling back the entire minute's batch of scheduled transfers.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void executeSingleScheduleSafe(ScheduledTransaction st, LocalDateTime executionTime) {
        Account fromAccount = accountRepository.findById(st.getFromAccountId())
                .orElseThrow(() -> new IllegalArgumentException("Source account missing"));

        // Build synthetic request
        TransactionRequest req = new TransactionRequest(
                fromAccount.getAccountNumber(),
                st.getToAccountNumber(),
                st.getAmount(),
                null, // No PIN required for pre-authorized scheduled transfers
                "Scheduled Transfer: " + st.getFrequency(),
                null,
                null,
                "SCHED-" + st.getId() + "-" + executionTime.toLocalDate()
        );

        // Execute transfer bypassing PIN but enforcing all other risk and lock rules
        transactionService.executeSecureTransfer(req, fromAccount.getUser().getUsername());

        // Calculate next execution date based on frequency
        LocalDateTime nextDate = calculateNextExecution(executionTime, st.getFrequency());

        if (nextDate != null) {
            st.setScheduledDate(nextDate);
        } else {
            st.setStatus(ScheduleStatus.EXECUTED.name());
        }

        scheduledTransactionRepository.save(st);
        log.info("Successfully executed scheduled transfer ID {}. Next run: {}", st.getId(), nextDate);
    }

    private LocalDateTime calculateNextExecution(LocalDateTime current, String frequency) {
        return switch (frequency.toUpperCase()) {
            case "DAILY" -> current.plusDays(1);
            case "WEEKLY" -> current.plusWeeks(1);
            case "MONTHLY" -> current.plusMonths(1);
            case "ONCE" -> null;
            default -> throw new IllegalArgumentException("Unknown frequency: " + frequency);
        };
    }

    @Transactional(readOnly = true)
    public List<ScheduledTransaction> getAllSchedules() {
        return scheduledTransactionRepository.findAll();
    }
}