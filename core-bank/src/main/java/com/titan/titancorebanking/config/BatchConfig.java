package com.titan.titancorebanking.config;

import com.titan.titancorebanking.batch.InterestProcessor;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.RepositoryItemWriter;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.batch.item.data.builder.RepositoryItemWriterBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class BatchConfig {

    private final AccountRepository accountRepository;

    // [MODIFIED] Externalized magic numbers for dynamic operational tuning.
    // បញ្ចូលអថេរសម្រាប់កំណត់ទំហំ Chunk និងចំនួន Skip ពី Configuration File ដើម្បីងាយស្រួលផ្លាស់ប្តូរពេលដាក់ឲ្យដំណើរការលើ Production ដោយមិនបាច់ Compile កូដឡើងវិញ។
    @Value("${batch.interest.chunk-size:1000}")
    private int chunkSize;

    @Value("${batch.interest.skip-limit:50}")
    private int skipLimit;

    @Bean
    public RepositoryItemReader<Account> reader() {
        log.debug("Initializing Account Reader with chunk size: {}", chunkSize);
        return new RepositoryItemReaderBuilder<Account>()
                .name("accountReader")
                .repository(accountRepository)
                /*
                 * STAFF ENGINEER NOTE:
                 * Using "findAll" is acceptable for medium datasets, but as your banking records scale,
                 * you should replace this with a custom repository method (e.g., "findActiveInterestEligibleAccounts")
                 * to push the filtering down to the PostgreSQL layer instead of loading dead accounts into RAM.
                 */
                .methodName("findAll")
                .sorts(Map.of("id", Sort.Direction.ASC))
                .pageSize(chunkSize)
                .build();
    }

    @Bean
    public InterestProcessor processor() {
        return new InterestProcessor();
    }

    @Bean
    public RepositoryItemWriter<Account> writer() {
        return new RepositoryItemWriterBuilder<Account>()
                .repository(accountRepository)
                .methodName("save")
                .build();
    }

    @Bean
    public Step interestStep(final JobRepository jobRepository,
                             final PlatformTransactionManager transactionManager) {

        // [MODIFIED] Implemented Fault Tolerance and Skip Logic for enterprise resilience.
        // បន្ថែមប្រព័ន្ធការពារកំហុស (Fault Tolerance)។ ប្រសិនបើមានបញ្ហាលើគណនីណាមួយ (ឧទាហរណ៍ ទិន្នន័យខូច) ប្រព័ន្ធនឹងរំលងគណនីនោះ ហើយបន្តដំណើរការគណនីរាប់ម៉ឺនទៀតជាធម្មតា មិនធ្វើឲ្យគាំងដំណើរការទាំងមូលឡើយ។
        return new StepBuilder("interestCalculationStep", jobRepository)
                .<Account, Account>chunk(chunkSize, transactionManager)
                .reader(reader())
                .processor(processor())
                .writer(writer())
                .faultTolerant()
                .skip(Exception.class) // Gracefully skip unhandled runtime exceptions during processing
                .skipLimit(skipLimit)  // Circuit break the entire job if failure threshold is breached
                .build();
    }

    @Bean
    public Job interestJob(final JobRepository jobRepository,
                           final Step interestStep) {
        return new JobBuilder("interestJob", jobRepository)
                .start(interestStep)
                .build();
    }
}