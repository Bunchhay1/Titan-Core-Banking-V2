package com.titan.titancorebanking.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDateTime;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class ColdStorageArchivalJob {

    private final EntityManagerFactory entityManagerFactory;
    private final ColdStorageWriter coldStorageWriter;

    @Bean
    public Job archiveOldTransactionsJob(JobRepository jobRepository, Step archiveStep) {
        return new JobBuilder("archiveOldTransactionsJob", jobRepository)
                .start(archiveStep)
                .build();
    }

    @Bean
    public Step archiveStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        return new StepBuilder("archiveStep", jobRepository)
                .<com.titan.titancorebanking.model.Transaction, com.titan.titancorebanking.model.Transaction>chunk(1000, transactionManager)
                .reader(oldTransactionReader())
                .processor(archiveProcessor())
                .writer(coldStorageWriter)
                .build();
    }

    @Bean
    public JpaPagingItemReader<com.titan.titancorebanking.model.Transaction> oldTransactionReader() {
        JpaPagingItemReader<com.titan.titancorebanking.model.Transaction> reader = new JpaPagingItemReader<>();
        reader.setEntityManagerFactory(entityManagerFactory);
        reader.setQueryString("SELECT t FROM Transaction t WHERE t.timestamp < :cutoffDate ORDER BY t.timestamp");
        reader.setParameterValues(java.util.Map.of("cutoffDate", LocalDateTime.now().minusYears(3)));
        reader.setPageSize(1000);
        return reader;
    }

    @Bean
    public ItemProcessor<com.titan.titancorebanking.model.Transaction, com.titan.titancorebanking.model.Transaction> archiveProcessor() {
        return transaction -> {
            log.info("Archiving transaction: {}", transaction.getId());
            return transaction;
        };
    }
}
