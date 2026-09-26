package com.titan.titancorebanking.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ColdStorageScheduler {

    private final JobLauncher jobLauncher;
    private final ColdStorageArchivalJob archivalJob;

    @Scheduled(cron = "0 0 2 1 * ?") // 2 AM on 1st of every month
    public void runArchival() {
        try {
            JobParameters params = new JobParametersBuilder()
                    .addLong("time", System.currentTimeMillis())
                    .toJobParameters();
            
            jobLauncher.run(archivalJob.archiveOldTransactionsJob(null, null), params);
            log.info("✅ Cold storage archival completed");
        } catch (Exception e) {
            log.error("❌ Cold storage archival failed", e);
        }
    }
}
