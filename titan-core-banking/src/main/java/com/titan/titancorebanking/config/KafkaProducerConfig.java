package com.titan.titancorebanking.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka Producer Configuration
 *
 * Supports two modes via KAFKA_SECURITY_PROTOCOL env var:
 *   PLAINTEXT  — local dev (default)
 *   SASL_SSL   — Confluent Cloud on Render (set KAFKA_SASL_USERNAME + KAFKA_SASL_PASSWORD)
 */
@Configuration
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // PLAINTEXT (local) or SASL_SSL (Confluent Cloud)
    @Value("${spring.kafka.security.protocol:PLAINTEXT}")
    private String securityProtocol;

    @Value("${spring.kafka.properties.sasl.mechanism:PLAIN}")
    private String saslMechanism;

    // Confluent Cloud API Key  → KAFKA_SASL_USERNAME on Render
    @Value("${spring.kafka.properties.sasl.jaas.username:}")
    private String saslUsername;

    // Confluent Cloud API Secret → KAFKA_SASL_PASSWORD on Render
    @Value("${spring.kafka.properties.sasl.jaas.password:}")
    private String saslPassword;

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> config = new HashMap<>();

        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        config.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);

        // Reliability
        config.put(ProducerConfig.ACKS_CONFIG, "1");
        config.put(ProducerConfig.RETRIES_CONFIG, 3);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);

        // Performance
        config.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        config.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        config.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432);
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        // ── Security ─────────────────────────────────────────────────────
        config.put("security.protocol", securityProtocol);

        if ("SASL_SSL".equalsIgnoreCase(securityProtocol)
                || "SASL_PLAINTEXT".equalsIgnoreCase(securityProtocol)) {

            config.put(SaslConfigs.SASL_MECHANISM, saslMechanism);
            config.put(SaslConfigs.SASL_JAAS_CONFIG, String.format(
                    "org.apache.kafka.common.security.plain.PlainLoginModule required "
                            + "username=\"%s\" password=\"%s\";",
                    saslUsername, saslPassword));
        }

        if ("SASL_SSL".equalsIgnoreCase(securityProtocol)
                || "SSL".equalsIgnoreCase(securityProtocol)) {
            config.put(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "https");
        }

        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}
