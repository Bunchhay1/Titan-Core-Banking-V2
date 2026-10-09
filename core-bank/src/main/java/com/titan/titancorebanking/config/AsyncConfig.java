package com.titan.titancorebanking.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executors;

@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig implements AsyncConfigurer {

    // [MODIFIED] Replaced traditional OS-bound thread pools with Java 21 Virtual Threads (Project Loom).
    // ផ្លាស់ប្តូរការដំណើរការ Async ពីការប្រើប្រាស់ OS Threads ធម្មតា ទៅប្រើប្រាស់ Virtual Threads របស់ Java 21 វិញ។ នេះអនុញ្ញាតឱ្យ Server របស់អ្នកអាចបង្កើត Threads រាប់លានដោយមិនស៊ី RAM ខ្លាំង ដែលស័ក្តិសមបំផុតសម្រាប់ប្រព័ន្ធ Core Banking ធំៗ។
    @Bean(name = "virtualThreadTaskExecutor")
    public AsyncTaskExecutor applicationTaskExecutor() {
        /*
         * STAFF ENGINEER NOTE:
         * Traditional thread pools allocate ~1MB of memory per thread. Under heavy load testing
         * (e.g., executing k6 stress tests), this leads to Thread Pool Exhaustion and OOM crashes.
         * Virtual Threads are mapped M:N to OS threads, taking mere bytes of memory, allowing
         * near-infinite concurrency for I/O bound tasks like Outbox processing and HTTP fallbacks.
         */
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        return new TaskExecutorAdapter(executor);
    }

    @Override
    public AsyncTaskExecutor getAsyncExecutor() {
        return applicationTaskExecutor();
    }

    // [MODIFIED] Added global exception handling to prevent silent failures in fire-and-forget background tasks.
    // បន្ថែមប្រព័ន្ធចាប់ Error សម្រាប់ Async Tasks។ ជាធម្មតា បើ Async Task (ឧ. ការបោះ Notification) គាំង វានឹងស្ងាត់បាត់ឈឹង (Silent Failure) ដោយគ្មានប្រាប់យើងឡើយ។ ឥឡូវនេះវានឹងលោត Alert ចូលទៅកាន់ Log យ៉ាងច្បាស់លាស់។
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> log.error(
                "CRITICAL: Uncaught exception in async execution. Method: {} | Parameters: {} | Error: {}",
                method.getName(), params, ex.getMessage(), ex
        );
    }
}