package com.titan.titancorebanking.config;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class BulkheadConfiguration {

    @Bean
    public BulkheadRegistry bulkheadRegistry() {
        BulkheadConfig criticalConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(100)
                .maxWaitDuration(Duration.ofMillis(500))
                .build();

        BulkheadConfig nonCriticalConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(20)
                .maxWaitDuration(Duration.ofMillis(100))
                .build();

        return BulkheadRegistry.of(java.util.Map.of(
                "critical", criticalConfig,
                "non-critical", nonCriticalConfig
        ));
    }

    @Bean
    public Bulkhead criticalBulkhead(BulkheadRegistry registry) {
        return registry.bulkhead("critical");
    }

    @Bean
    public Bulkhead nonCriticalBulkhead(BulkheadRegistry registry) {
        return registry.bulkhead("non-critical");
    }
}
