package com.titan.titancorebanking.service;

import com.titan.titancorebanking.model.ScheduledTransaction; // ✅ Correct Import
import com.titan.titancorebanking.enums.ScheduleStatus;
import com.titan.titancorebanking.repository.ScheduledTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScheduledTransactionService {
    private final ScheduledTransactionRepository scheduledTransactionRepository;

    @Scheduled(cron = "0 * * * * *")
    @Transactional
    public void processScheduledTransactions() {
        // Logic execution here...
    }

    public List<ScheduledTransaction> getAllSchedules() {
        return scheduledTransactionRepository.findAll();
    }
}