package com.titan.notifications.batch;

import com.titan.notifications.model.NotificationAudit;
import com.titan.notifications.repository.NotificationAuditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.*;
import org.springframework.batch.core.configuration.annotation.*;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.*;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import jakarta.persistence.EntityManagerFactory;
import java.io.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.GZIPOutputStream;

@Configuration
@Slf4j
@RequiredArgsConstructor
public class ArchivalBatchConfig {
    
    private final JobLauncher jobLauncher;
    private final EntityManagerFactory entityManagerFactory;
    private final NotificationAuditRepository auditRepository;
    private final S3Client s3Client;
    private Job archivalJobBean;
    
    @Bean
    public Job archivalJob(JobRepository jobRepository, Step archivalStep) {
        this.archivalJobBean = new JobBuilder("archivalJob", jobRepository)
                .start(archivalStep)
                .build();
        return this.archivalJobBean;
    }
    
    @Bean
    public Step archivalStep(JobRepository jobRepository, ItemReader<NotificationAudit> reader,
                             ItemWriter<NotificationAudit> writer,
                             PlatformTransactionManager transactionManager) {
        return new StepBuilder("archivalStep", jobRepository)
                .<NotificationAudit, NotificationAudit>chunk(1000, transactionManager)
                .reader(reader)
                .writer(writer)
                .build();
    }
    
    @Bean
    public JpaPagingItemReader<NotificationAudit> archivalReader() {
        JpaPagingItemReader<NotificationAudit> reader = new JpaPagingItemReader<>();
        reader.setEntityManagerFactory(entityManagerFactory);
        reader.setQueryString("SELECT n FROM NotificationAudit n WHERE n.status = 'DELIVERED' AND n.sentAt < :cutoffDate");
        reader.setParameterValues(Map.of("cutoffDate", LocalDateTime.now().minusDays(30)));
        reader.setPageSize(1000);
        return reader;
    }
    
    @Bean
    public ItemWriter<NotificationAudit> archivalWriter() {
        return items -> {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(baos);
                 ObjectOutputStream oos = new ObjectOutputStream(gzip)) {
                
                for (NotificationAudit audit : items) {
                    oos.writeObject(audit);
                }
            }
            
            String key = "archives/notifications-" + LocalDateTime.now() + ".gz";
            s3Client.putObject(
                PutObjectRequest.builder()
                    .bucket("titan-compliance-archive")
                    .key(key)
                    .storageClass(StorageClass.GLACIER)
                    .build(),
                RequestBody.fromBytes(baos.toByteArray())
            );
            
            log.info("📦 Archived {} notifications to S3 Glacier: {}", items.size(), key);
        };
    }
    
    @Scheduled(cron = "0 0 2 * * SUN")
    public void runWeeklyArchival() {
        try {
            JobParameters params = new JobParametersBuilder()
                .addLong("time", System.currentTimeMillis())
                .toJobParameters();
            jobLauncher.run(archivalJobBean, params);
            log.info("✅ Weekly archival job completed");
        } catch (Exception e) {
            log.error("❌ Archival job failed: {}", e.getMessage());
        }
    }
}
