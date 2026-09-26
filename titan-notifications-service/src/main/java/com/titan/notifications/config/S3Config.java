package com.titan.notifications.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

@Configuration
public class S3Config {

    @Bean
    @ConditionalOnMissingBean(S3Client.class)
    public S3Client s3Client() {
        // No-op S3 client for local dev - archival batch is disabled via spring.batch.job.enabled=false
        return S3Client.builder()
                .region(Region.AP_SOUTHEAST_1)
                .credentialsProvider(AnonymousCredentialsProvider.create())
                .endpointOverride(URI.create("http://localhost:4566")) // localstack placeholder
                .build();
    }
}
