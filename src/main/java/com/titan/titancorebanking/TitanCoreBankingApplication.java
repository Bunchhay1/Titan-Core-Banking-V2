package com.titan.titancorebanking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Titan Core Banking Engine
 * High-Frequency, Multi-Threaded Core Banking System.
 * Optimized for Java 21 Virtual Threads and ACID Compliance.
 */
@SpringBootApplication
@EnableCaching
@EnableScheduling
@EnableAsync
public class TitanCoreBankingApplication {

    public static void main(String[] args) {
        SpringApplication.run(TitanCoreBankingApplication.class, args);
    }
}