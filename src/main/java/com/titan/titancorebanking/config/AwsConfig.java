package com.titan.titancorebanking.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
@Slf4j
public class AwsConfig {

    @Bean
    public S3Client s3Client() {
        try {
            // Attempt to use system/profile credentials (Production behavior)
            return S3Client.builder()
                    .credentialsProvider(DefaultCredentialsProvider.create())
                    .region(Region.AP_SOUTHEAST_1) // Use your primary AWS region
                    .build();
        } catch (Exception e) {
            log.warn("AWS credentials not found. Falling back to anonymous credentials for local development.");
            // Fallback so local development doesn't crash on boot without AWS keys
            return S3Client.builder()
                    .credentialsProvider(AnonymousCredentialsProvider.create())
                    .region(Region.AP_SOUTHEAST_1)
                    .build();
        }
    }
}