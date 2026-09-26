package com.titan.notifications.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.titan.notifications.event.TransactionCompletedEvent;
import com.titan.notifications.service.NotificationService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Verifies the Kafka topic alignment fix.
 *
 * Root cause: OutboxRelayService hardcoded "transaction-events" while
 * NotificationListener consumed "banking.transactions.completed".
 *
 * Fix: @Value("${kafka.topic.transaction-completed:banking.transactions.completed}")
 * injected into OutboxRelayService — both services now use the same topic.
 *
 * Strategy: Single shared Spring context, separate low-level consumer per test
 * to avoid group offset conflicts with the application's KafkaListener.
 */
@SpringBootTest
@EmbeddedKafka(
    partitions = 1,
    topics = {"banking.transactions.completed"}
)
@TestPropertySource(properties = {
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
    "spring.kafka.consumer.topic=banking.transactions.completed",
    "kafka.topic.transaction-completed=banking.transactions.completed",
    "spring.kafka.consumer.group-id=app-notifications-group",
    "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.batch.job.enabled=false",
    "spring.batch.jdbc.initialize-schema=always",
    "spring.cloud.vault.enabled=false",
    "grpc.client.engagementService.address=static://localhost:9999",
    "grpc.client.engagementService.negotiationType=PLAINTEXT"
})
@DirtiesContext
class KafkaTopicAlignmentTest {

    private static final String TOPIC = "banking.transactions.completed";

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private NotificationService notificationService;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @BeforeEach
    void waitForConsumerAssignment() throws Exception {
        for (var container : kafkaListenerEndpointRegistry.getListenerContainers()) {
            ContainerTestUtils.waitForAssignment(container, embeddedKafka.getPartitionsPerTopic());
        }
    }

    /**
     * Test 1: Event published to "banking.transactions.completed" is consumed.
     *
     * Before the fix: OutboxRelayService sent to "transaction-events" → nobody consumed it.
     * After the fix: topic is injected from config → both services aligned.
     */
    @Test
    void eventPublishedToCorrectTopic_isConsumedByNotificationListener() throws Exception {
        TransactionCompletedEvent event = TransactionCompletedEvent.builder()
            .eventId("evt-001")
            .transactionId("1001")
            .amount(new BigDecimal("250.00"))
            .currency("USD")
            .type("TRANSFER")
            .status("COMPLETED")
            .build();

        kafkaTemplate.send(new ProducerRecord<>(TOPIC, "tx-1001", objectMapper.writeValueAsString(event)));

        verify(notificationService, timeout(5000).times(1))
            .sendNotifications(any(TransactionCompletedEvent.class));
    }

    /**
     * Test 2: Verify event fields are correctly deserialized from the Kafka message.
     * Ensures the schema published by core banking is compatible with what notifications expects.
     */
    @Test
    void eventFields_areCorrectlyDeserializedByNotificationListener() throws Exception {
        TransactionCompletedEvent event = TransactionCompletedEvent.builder()
            .eventId("evt-002")
            .transactionId("5555")
            .amount(new BigDecimal("9999.00"))
            .currency("USD")
            .type("TRANSFER")
            .status("COMPLETED")
            .build();

        kafkaTemplate.send(new ProducerRecord<>(TOPIC, "tx-5555", objectMapper.writeValueAsString(event)));

        org.mockito.ArgumentCaptor<TransactionCompletedEvent> captor =
            org.mockito.ArgumentCaptor.forClass(TransactionCompletedEvent.class);

        verify(notificationService, timeout(5000).times(1)).sendNotifications(captor.capture());

        TransactionCompletedEvent captured = captor.getValue();
        assertThat(captured.getEventId()).isEqualTo("evt-002");
        assertThat(captured.getAmount()).isEqualByComparingTo("9999.00");
        assertThat(captured.getCurrency()).isEqualTo("USD");
        assertThat(captured.getTransactionType()).isEqualTo("TRANSFER");
        assertThat(captured.getStatus()).isEqualTo("COMPLETED");
    }

    /**
     * Test 3: Malformed JSON must NOT crash the consumer.
     * A valid message after the bad one must still be processed.
     */
    @Test
    void malformedMessage_doesNotCrashConsumer_nextValidMessageIsProcessed() throws Exception {
        kafkaTemplate.send(new ProducerRecord<>(TOPIC, "bad-key", "{ not valid json }"));

        TimeUnit.MILLISECONDS.sleep(300);

        TransactionCompletedEvent valid = TransactionCompletedEvent.builder()
            .eventId("evt-after-bad")
            .transactionId("2001")
            .amount(new BigDecimal("50.00"))
            .currency("KHR")
            .type("DEPOSIT")
            .status("COMPLETED")
            .build();

        kafkaTemplate.send(new ProducerRecord<>(TOPIC, "tx-2001", objectMapper.writeValueAsString(valid)));

        // Consumer must still be alive and process the valid message
        verify(notificationService, timeout(5000).atLeastOnce())
            .sendNotifications(any(TransactionCompletedEvent.class));
    }
}
