package com.titan.event.consumer.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Base Kafka Consumer Configuration (library).
 *
 * Supports three security modes controlled by KAFKA_SECURITY_PROTOCOL:
 *   PLAINTEXT  — local dev, no credentials
 *   SASL_SSL   — Confluent Cloud (set KAFKA_SASL_USERNAME + KAFKA_SASL_PASSWORD)
 *   SSL        — mTLS with keystores
 *
 * Beans are annotated @ConditionalOnMissingBean so any service that declares
 * its own ConsumerFactory / kafkaListenerContainerFactory takes priority.
 */
@Configuration
@EnableKafka
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:titan-consumer-group}")
    private String groupId;

    // ── Security mode ────────────────────────────────────────────────────
    @Value("${spring.kafka.security.protocol:PLAINTEXT}")
    private String securityProtocol;

    // ── SASL_SSL (Confluent Cloud) ───────────────────────────────────────
    @Value("${spring.kafka.properties.sasl.mechanism:PLAIN}")
    private String saslMechanism;

    @Value("${spring.kafka.properties.sasl.jaas.username:}")
    private String saslUsername;

    @Value("${spring.kafka.properties.sasl.jaas.password:}")
    private String saslPassword;

    // ── SSL / mTLS (optional) ────────────────────────────────────────────
    @Value("${spring.kafka.ssl.trust-store-location:}")
    private String trustStoreLocation;

    @Value("${spring.kafka.ssl.trust-store-password:}")
    private String trustStorePassword;

    @Value("${spring.kafka.ssl.key-store-location:}")
    private String keyStoreLocation;

    @Value("${spring.kafka.ssl.key-store-password:}")
    private String keyStorePassword;

    @Bean
    @ConditionalOnMissingBean(name = "baseConsumerFactory")
    public ConsumerFactory<String, String> baseConsumerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
        config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);
        config.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
        config.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10000);

        // ── Apply security config based on protocol ──────────────────────
        config.put("security.protocol", securityProtocol);

        switch (securityProtocol.toUpperCase()) {
            case "SASL_SSL" -> {
                // Confluent Cloud — SASL over TLS
                config.put(SaslConfigs.SASL_MECHANISM, saslMechanism);
                config.put(SaslConfigs.SASL_JAAS_CONFIG, String.format(
                        "org.apache.kafka.common.security.plain.PlainLoginModule required "
                                + "username=\"%s\" password=\"%s\";",
                        saslUsername, saslPassword));
                config.put(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "https");
            }
            case "SASL_PLAINTEXT" -> {
                config.put(SaslConfigs.SASL_MECHANISM, saslMechanism);
                config.put(SaslConfigs.SASL_JAAS_CONFIG, String.format(
                        "org.apache.kafka.common.security.plain.PlainLoginModule required "
                                + "username=\"%s\" password=\"%s\";",
                        saslUsername, saslPassword));
            }
            case "SSL" -> {
                // mTLS with local keystores
                if (!trustStoreLocation.isBlank()) {
                    config.put("ssl.truststore.location", trustStoreLocation.replace("file:", ""));
                    config.put("ssl.truststore.password", trustStorePassword);
                }
                if (!keyStoreLocation.isBlank()) {
                    config.put("ssl.keystore.location", keyStoreLocation.replace("file:", ""));
                    config.put("ssl.keystore.password", keyStorePassword);
                }
                config.put(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "");
            }
            // PLAINTEXT — no extra config needed
        }

        return new DefaultKafkaConsumerFactory<>(config);
    }

    /**
     * Named "baseKafkaListenerContainerFactory" to avoid clashing with
     * any service-level "kafkaListenerContainerFactory" bean.
     */
    @Bean
    @ConditionalOnMissingBean(name = "kafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(baseConsumerFactory());
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        return factory;
    }
}
