package com.titan.titancorebanking.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync // ✅ This activates the "Background Threads"
public class AsyncConfig {
}