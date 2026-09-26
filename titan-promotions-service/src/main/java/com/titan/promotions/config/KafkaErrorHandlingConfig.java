package com.titan.promotions.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import java.util.HashMap;
import java.util.Map;

@Configuration
@Slf4j
public class KafkaErrorHandlingConfig {

    @Value("${spring.kafka.dlq.topic:banking.transactions.dlq}")
    private String dlqTopic;

    /**
     * Error handler with exponential back-off (3 retries: 1 s, 2 s, 4 s)
     * and a Dead-Letter Publishing Recoverer that routes failed messages to the DLQ.
     *
     * Used by kafkaListenerContainerFactory (see below).
     */
    @Bean
    public CommonErrorHandler errorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
            (record, ex) -> {
                log.error("[ERROR_HANDLER] Sending to DLQ after retries exhausted: topic={}, key={}, error={}",
                    record.topic(), record.key(), ex.getMessage(), ex);
                return new org.apache.kafka.common.TopicPartition(dlqTopic, 0);
            });

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10_000L);

        return new DefaultErrorHandler(recoverer, backOff);
    }

    /**
     * Kafka listener container factory wired with manual acknowledgement and
     * the custom error handler above.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            CommonErrorHandler errorHandler) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties()
            .setAckMode(org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
