package com.titan.titancorebanking.config;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadConfig;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Enterprise Resilience Configuration.
 * Implements the Bulkhead pattern to physically isolate thread pools and semaphores.
 * This ensures that a massive spike in non-critical requests (like fetching history)
 * cannot consume resources needed for critical path operations (like fund transfers).
 */
@Configuration
public class BulkheadConfiguration {

    @Bean
    public BulkheadRegistry bulkheadRegistry() {
        BulkheadConfig config = BulkheadConfig.custom()
                .maxConcurrentCalls(50) // Limit concurrent non-critical operations
                .maxWaitDuration(Duration.ofMillis(500)) // Fail fast if saturated
                .build();
        return BulkheadRegistry.of(config);
    }

    @Bean
    public ThreadPoolBulkheadRegistry threadPoolBulkheadRegistry() {
        ThreadPoolBulkheadConfig config = ThreadPoolBulkheadConfig.custom()
                .maxThreadPoolSize(20)
                .coreThreadPoolSize(10)
                .queueCapacity(50)
                .build();
        return ThreadPoolBulkheadRegistry.of(config);
    }
}