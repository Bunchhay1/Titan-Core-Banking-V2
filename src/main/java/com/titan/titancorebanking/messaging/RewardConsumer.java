package com.titan.titancorebanking.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.enums.TransactionStatus;
import com.titan.titancorebanking.enums.TransactionType;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.TransactionRepository;
import com.titan.titancorebanking.service.DoubleEntryService;
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
    private final DoubleEntryService doubleEntryService;

    private static final String ACK_TOPIC = "banking.rewards.acknowledgment";

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

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(record.value(), Map.class);

            String eventId = (String) payload.get("eventId");
            Long accountId = parseLong(payload.get("accountId"));
            BigDecimal amount = parseBigDecimal(payload.get("rewardAmount"));
            String description = (String) payload.getOrDefault("description", "Promotion Reward");
            String currency = (String) payload.getOrDefault("currency", "USD");

            // 1. Database-backed Idempotency check
            Optional<Transaction> existingReward = transactionRepository.findByIdempotencyKey(eventId);
            if (existingReward.isPresent()) {
                log.info("[REWARD] Event {} already processed. Acknowledging duplicate.", eventId);
                publishAck(eventId, "SUCCESS", null);
                return;
            }

            if (accountId == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("[REWARD] Skipping invalid reward event: accountId={}, amount={}", accountId, amount);
                publishAck(eventId, "FAILED", "Invalid accountId or amount");
                return;
            }

            // 2. Pessimistic Row Lock on Account
            Account account = accountRepository.findByIdWithLock(accountId)
                    .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

            // 3. Credit Balance
            account.setBalance(account.getBalance().add(amount));
            accountRepository.save(account);

            // 4. Save Audit Record
            String transactionRef = "REWARD-" + eventId.substring(0, 8).toUpperCase();
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

            // 5. System Ledger Double-Entry (System Promo GL Account ID 2 -> Customer Account)
            doubleEntryService.createDoubleEntry(rewardTx.getId(), 2L, account.getId(), amount, description, "SYSTEM_PROMO");

            // 6. Acknowledge Success
            publishAck(eventId, "SUCCESS", null);

            // 7. Trigger Notification asynchronously
            Thread.ofVirtual().start(() -> {
                try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
                publishRewardNotification(eventId, accountId, amount, account.getBalance(), description, currency);
            });

        } catch (Exception e) {
            log.error("[REWARD] Failed to process reward event: key={}, error={}", record.key(), e.getMessage(), e);
        }
    }

    private void publishAck(String rewardEventId, String status, String errorMessage) {
        try {
            Map<String, Object> ack = new HashMap<>();
            ack.put("rewardEventId", rewardEventId);
            ack.put("status", status);
            if (errorMessage != null) ack.put("error", errorMessage);

            kafkaTemplate.send(ACK_TOPIC, rewardEventId, ack);
        } catch (Exception e) {
            log.error("[REWARD] Failed to publish ACK for eventId={}: {}", rewardEventId, e.getMessage());
        }
    }

    private Long parseLong(Object value) {
        if (value == null) return null;
        try {
            if (value instanceof Number n) return n.longValue();
            return Long.parseLong(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal parseBigDecimal(Object value) {
        if (value == null) return null;
        try {
            if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
            return new BigDecimal(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private void publishRewardNotification(String rewardEventId, Long accountId, BigDecimal amount,
                                           BigDecimal newBalance, String description, String currency) {
        try {
            accountRepository.findById(accountId).ifPresent(account -> {
                String username = account.getUser() != null ? account.getUser().getUsername() : "Customer";

                Map<String, String> metadata = new HashMap<>();
                metadata.put("accountId", accountId.toString());
                metadata.put("rewardType", "DEPOSIT_BONUS");
                metadata.put("receiverName", username);
                metadata.put("receiverAccount", account.getAccountNumber());
                metadata.put("newBalance", newBalance.toPlainString());
                metadata.put("bonusAmount", amount.toPlainString());

                Map<String, Object> event = new HashMap<>();
                event.put("eventId", rewardEventId);
                event.put("eventType", "TransactionCompleted");
                event.put("eventVersion", "1.0");
                event.put("timestamp", java.time.Instant.now().toString());
                event.put("transactionId", rewardEventId);
                event.put("amount", amount);
                event.put("currency", currency);
                event.put("type", "PROMOTION");
                event.put("status", "SUCCESS");
                event.put("targetAccountNumber", account.getAccountNumber());
                event.put("username", username);
                event.put("note", description);
                event.put("metadata", metadata);

                kafkaTemplate.send("banking.transactions.completed", rewardEventId, event);
            });
        } catch (Exception e) {
            log.error("[REWARD] Failed to publish notification for eventId={}: {}", rewardEventId, e.getMessage());
        }
    }
}