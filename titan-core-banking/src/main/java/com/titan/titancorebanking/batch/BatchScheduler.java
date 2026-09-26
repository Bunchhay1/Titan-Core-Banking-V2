package com.titan.titancorebanking.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class BatchScheduler {

    private final JobLauncher jobLauncher;
    private final Job interestJob; // ✅ ឥឡូវវានឹងស្គាល់ Bean នេះមកពី BatchConfig

    // Run every minute for testing (Cron: 0 * * * * *)
    // Or every midnight: "0 0 0 * * *"
    @Scheduled(cron = "0 * * * * *")
    public void runInterestCalculationJob() {
        try {
            log.info("🚀 Starting Interest Calculation Batch Job...");

            // Add time parameter to make each run unique
            JobParameters jobParameters = new JobParametersBuilder()
                    .addLong("time", System.currentTimeMillis())
                    .toJobParameters();

            jobLauncher.run(interestJob, jobParameters);

            log.info("✅ Batch Job Completed Successfully!");
        } catch (Exception e) {
            log.error("❌ Batch Job Failed: ", e);
        }
    }
}