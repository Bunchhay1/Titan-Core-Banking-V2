package com.titan.promotions.streams;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.titan.promotions.event.TransactionCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.serializer.JsonSerde;

import java.time.Duration;

@Configuration
@Slf4j
@RequiredArgsConstructor
public class UserBehaviorAggregator {
    
    private final ObjectMapper objectMapper;
    
    @Bean
    public KStream<String, TransactionCompletedEvent> buildUserBehaviorStream(StreamsBuilder builder) {
        KStream<String, TransactionCompletedEvent> stream = builder
            .stream("banking.transactions.completed", Consumed.with(Serdes.String(), new JsonSerde<>(TransactionCompletedEvent.class, objectMapper)));
        
        // Windowed aggregation: Count QR payments per user in 24h window
        stream
            .filter((key, event) -> "QR_PAYMENT".equals(event.getTransactionType()))
            .groupBy((key, event) -> event.getAccountId().toString(), Grouped.with(Serdes.String(), new JsonSerde<>(TransactionCompletedEvent.class, objectMapper)))
            .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofHours(24)))
            .count()
            .toStream()
            .filter((windowedKey, count) -> count >= 5)
            .foreach((windowedKey, count) -> {
                log.info("Milestone reached: User {} made {} QR payments in 24h", windowedKey.key(), count);
                // Trigger MilestoneReachedEvent
            });
        
        return stream;
    }
}
