package com.titan.titancorebanking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.enums.TransactionStatus;
import com.titan.titancorebanking.enums.TransactionType;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class RewardConsumer {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private static final String ACK_TOPIC = "banking.rewards.acknowledgment";
    private static final String DLQ_TOPIC = "banking.transactions.poison";

    @KafkaListener(
            topics = "banking.rewards.granted",
            groupId = "core-banking-rewards",
            properties = {
                    "value.deserializer=org.apache.kafka.common.serialization.StringDeserializer",
                    "key.deserializer=org.apache.kafka.common.serialization.StringDeserializer"
            }
    )
    @Transactional
    public void consumeRewardGranted(ConsumerRecord<String, String> record) {
        log.info("[REWARD] Received reward event: key={}, offset={}", record.key(), record.offset());

        String eventId = "UNKNOWN";
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(record.value(), Map.class);
            eventId = (String) payload.getOrDefault("eventId", record.key());

            Long accountId = parseLong(payload.get("accountId"));
            BigDecimal amount = parseBigDecimal(payload.get("rewardAmount"));
            String description = (String) payload.getOrDefault("description", "Promotion Reward");
            String currency = (String) payload.getOrDefault("currency", "USD");

            Optional<Transaction> existingReward = transactionRepository.findByIdempotencyKey(eventId);
            if (existingReward.isPresent()) {
                log.info("[REWARD] Event {} already processed - skipping duplicate", eventId);
                publishAck(eventId, "SUCCESS", null);
                return;
            }

            if (accountId == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("[REWARD] Invalid payload - rejecting event: accountId={}, amount={}", accountId, amount);
                publishAck(eventId, "FAILED", "Invalid accountId or amount");
                return;
            }

            Optional<Account> accountOpt = accountRepository.findByIdWithLock(accountId);
            if (accountOpt.isEmpty()) {
                log.error("[REWARD] Account {} not found", accountId);
                publishAck(eventId, "FAILED", "Account not found");
                return;
            }

            Account account = accountOpt.get();
            BigDecimal balanceBefore = account.getBalance();
            account.setBalance(balanceBefore.add(amount));
            accountRepository.save(account);

            String transactionRef = "REWARD-" + eventId.substring(0, Math.min(8, eventId.length())).toUpperCase();
            Transaction rewardTx = Transaction.builder()
                    .transactionType(TransactionType.DEPOSIT)
                    .amount(amount)
                    .fromAccount(null)
                    .toAccount(account)
                    .timestamp(LocalDateTime.now())
                    .status(TransactionStatus.SUCCESS)
                    .note(description)
                    .idempotencyKey(eventId)
                    .transactionReference(transactionRef)
                    .build();
            transactionRepository.save(rewardTx);

            publishAck(eventId, "SUCCESS", null);

            // Fire async notification so it doesn't block the Kafka consumer thread
            Thread.ofVirtual().start(() ->
                    publishRewardNotification(eventId, accountId, amount, account.getBalance(), description, currency)
            );

        } catch (Exception e) {
            // STAFF ENGINEER FIX: Catch unrecoverable exceptions and route to DLQ to prevent partition blocking
            log.error("[REWARD] Poison Pill Detected! Routing event {} to DLQ. Error: {}", eventId, e.getMessage(), e);
            sendToDeadLetterQueue(record, e.getMessage());
        }
    }

    private void sendToDeadLetterQueue(ConsumerRecord<String, String> record, String errorCause) {
        try {
            Map<String, Object> dlqPayload = new HashMap<>();
            dlqPayload.put("originalKey", record.key());
            dlqPayload.put("originalValue", record.value());
            dlqPayload.put("topic", record.topic());
            dlqPayload.put("partition", record.partition());
            dlqPayload.put("offset", record.offset());
            dlqPayload.put("error", errorCause);
            dlqPayload.put("timestamp", LocalDateTime.now().toString());

            kafkaTemplate.send(DLQ_TOPIC, record.key(), objectMapper.writeValueAsString(dlqPayload));
        } catch (Exception dlqEx) {
            log.error("[REWARD] CRITICAL: Failed to write to DLQ: {}", dlqEx.getMessage());
        }
    }

    private void publishAck(String rewardEventId, String status, String errorMessage) {
        try {
            Map<String, Object> ack = new HashMap<>();
            ack.put("rewardEventId", rewardEventId);
            ack.put("status", status);
            if (errorMessage != null) {
                ack.put("error", errorMessage);
            }
            kafkaTemplate.send(ACK_TOPIC, rewardEventId, ack);
        } catch (Exception e) {
            log.error("[REWARD] Failed to publish ACK: {}", e.getMessage());
        }
    }

    private Long parseLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(value.toString()); } catch (Exception e) { return null; }
    }

    private BigDecimal parseBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(value.toString()); } catch (Exception e) { return null; }
    }

    private void publishRewardNotification(String rewardEventId, Long accountId, BigDecimal amount,
                                           BigDecimal newBalance, String description, String currency) {
        try {
            // Stagger slightly to allow DB commit to settle before pushing to the user's phone
            Thread.sleep(1500);

            Optional<Account> accountOpt = accountRepository.findById(accountId);
            if (accountOpt.isEmpty()) return;

            Account account = accountOpt.get();
            String username = account.getUser() != null ? account.getUser().getUsername() : "Customer";
            String accountNumber = account.getAccountNumber();

            Map<String, String> metadata = new HashMap<>();
            metadata.put("accountId", accountId.toString());
            metadata.put("rewardType", "DEPOSIT_BONUS");
            metadata.put("receiverName", username);
            metadata.put("receiverAccount", accountNumber);
            metadata.put("newBalance", newBalance != null ? newBalance.toPlainString() : "");
            metadata.put("bonusAmount", amount != null ? amount.toPlainString() : "");

            Map<String, Object> event = new HashMap<>();
            event.put("eventId", rewardEventId);
            event.put("eventType", "TransactionCompleted");
            event.put("correlationId", rewardEventId);
            event.put("amount", amount);
            event.put("currency", currency);
            event.put("type", "PROMOTION");
            event.put("status", "SUCCESS");
            event.put("targetAccountNumber", accountNumber);
            event.put("username", username);
            event.put("note", description);
            event.put("metadata", metadata);

            kafkaTemplate.send("banking.transactions.completed", rewardEventId, event);
        } catch (Exception e) {
            log.error("[REWARD] Failed to publish notification for eventId={}", rewardEventId);
        }
    }
}