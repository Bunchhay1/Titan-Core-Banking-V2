package com.titan.titancorebanking.config;

import com.titan.titancorebanking.batch.InterestProcessor;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.RepositoryItemWriter;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.batch.item.data.builder.RepositoryItemWriterBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

@Configuration
@RequiredArgsConstructor
public class BatchConfig {

    private final AccountRepository accountRepository;

    // 1️⃣ READER: ទាញទិន្នន័យ Account ពី Database
    @Bean
    public RepositoryItemReader<Account> reader() {
        return new RepositoryItemReaderBuilder<Account>()
                .name("accountReader")
                .repository(accountRepository)
                .methodName("findAll")
                .sorts(Map.of("id", Sort.Direction.ASC))
                .pageSize(1000) // Chunk-based: 1000 records at a time
                .build();
    }

    // 2️⃣ PROCESSOR: គណនាការប្រាក់ (ហៅ Class ដែលមានស្រាប់)
    @Bean
    public InterestProcessor processor() {
        return new InterestProcessor();
    }

    // 3️⃣ WRITER: Save ទិន្នន័យដែលគណនារួចទៅ Database វិញ
    @Bean
    public RepositoryItemWriter<Account> writer() {
        return new RepositoryItemWriterBuilder<Account>()
                .repository(accountRepository)
                .methodName("save")
                .build();
    }

    // 4️⃣ STEP: ផ្គុំ Reader + Processor + Writer
    @Bean
    public Step interestStep(JobRepository jobRepository,
                             PlatformTransactionManager transactionManager) {
        return new StepBuilder("interestCalculationStep", jobRepository)
                .<Account, Account>chunk(1000, transactionManager) // 1000 records per chunk
                .reader(reader())
                .processor(processor())
                .writer(writer())
                .build();
    }

    // 5️⃣ JOB: បង្កើត Job ឈ្មោះ "interestJob"
    @Bean
    public Job interestJob(JobRepository jobRepository,
                           Step interestStep) {
        return new JobBuilder("interestJob", jobRepository)
                .start(interestStep)
                .build();
    }
}