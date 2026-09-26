package com.titan.promotions.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * KafkaProducerConfig
 *
 * Provides two KafkaTemplate beans:
 *
 *  1. KafkaTemplate<String, String>  — @Primary
 *     Used by: OutboxProcessor, DeadLetterQueueHandler, KafkaErrorHandlingConfig
 *     Value serializer: StringSerializer (payload already JSON-encoded as String)
 *
 *  2. KafkaTemplate<String, Object>
 *     Used by: EnhancedPromotionService (sends RewardDispatchEvent POJOs)
 *     Value serializer: JsonSerializer (serializes any object to JSON)
 */
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    // ── Shared base properties ─────────────────────────────────────────────

    private Map<String, Object> baseProducerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,    bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG,                 "all");
        props.put(ProducerConfig.RETRIES_CONFIG,              3);
        props.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG,     500);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,   true);
        props.put(ProducerConfig.LINGER_MS_CONFIG,            5);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG,           16384);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG,     "snappy");
        return props;
    }

    // ── Bean 1: KafkaTemplate<String, String>  (Primary) ──────────────────

    @Bean
    @Primary
    public ProducerFactory<String, String> stringProducerFactory() {
        Map<String, Object> props = baseProducerProps();
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    @Primary
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(stringProducerFactory());
    }

    // ── Bean 2: KafkaTemplate<String, Object> ─────────────────────────────

    @Bean
    public ProducerFactory<String, Object> objectProducerFactory() {
        Map<String, Object> props = baseProducerProps();
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        // Do not add type headers — keeps payloads clean for downstream consumers
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, Object> objectKafkaTemplate() {
        return new KafkaTemplate<>(objectProducerFactory());
    }
}
