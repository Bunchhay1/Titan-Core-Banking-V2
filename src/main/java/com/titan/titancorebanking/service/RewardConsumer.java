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

/**
 * RewardConsumer
 *
 * Listens to banking.rewards.granted (published by titan-promotions-service
 * via its OutboxProcessor every 5 seconds).
 *
 * For each REWARD_GRANTED event:
 *   1. Resolves the target account by accountId.
 *   2. Credits the rewardAmount to the account balance (e.g. $2 deposit bonus).
 *   3. Saves a DEPOSIT transaction record so the mobile app sees it in history.
 *   4. Publishes an ACK to banking.rewards.acknowledgment so the promotions
 *      service can update the reward status to DISBURSED.
 *
 * This is the MISSING LINK that was preventing the $2 from appearing on the
 * mobile UI after a qualifying deposit.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RewardConsumer {

    private final AccountRepository      accountRepository;
    private final TransactionRepository  transactionRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper           objectMapper;

    private static final String ACK_TOPIC = "banking.rewards.acknowledgment";

    @KafkaListener(
        topics    = "banking.rewards.granted",
        groupId   = "core-banking-rewards",
        properties = {
            "value.deserializer=org.apache.kafka.common.serialization.StringDeserializer",
            "key.deserializer=org.apache.kafka.common.serialization.StringDeserializer"
        }
    )
    @Transactional
    public void consumeRewardGranted(ConsumerRecord<String, String> record) {
        log.info("[REWARD] Received reward event: key={}, offset={}",
            record.key(), record.offset());

        try {
            // ── 1. Deserialize payload ────────────────────────────────────────
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(record.value(), Map.class);

            String  eventId      = (String)  payload.get("eventId");
            String  rewardEventId = eventId;
            Long    accountId    = parseLong(payload.get("accountId"));
            BigDecimal amount    = parseBigDecimal(payload.get("rewardAmount"));
            String  description  = (String)  payload.getOrDefault("description", "Promotion Reward");
            String  currency     = (String)  payload.getOrDefault("currency", "USD");

            log.info("[REWARD] Processing: eventId={}, accountId={}, amount={}, description={}",
                eventId, accountId, amount, description);

            // ── 2. Idempotency check: skip if already processed ───────────────
            Optional<Transaction> existingReward = transactionRepository.findByIdempotencyKey(rewardEventId);
            if (existingReward.isPresent()) {
                log.info("[REWARD] ⚠️ Event {} already processed — skipping duplicate", rewardEventId);
                publishAck(rewardEventId, "SUCCESS", null);  // acknowledge anyway
                return;
            }

            // ── 3. Guard: skip if amount is null or zero ──────────────────────
            if (accountId == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("[REWARD] Skipping invalid reward event: accountId={}, amount={}", accountId, amount);
                publishAck(rewardEventId, "FAILED", "Invalid accountId or amount");
                return;
            }

            // ── 4. Resolve account ────────────────────────────────────────────
            Optional<Account> accountOpt = accountRepository.findByIdWithLock(accountId);
            if (accountOpt.isEmpty()) {
                log.error("[REWARD] Account not found: accountId={} — cannot credit reward", accountId);
                publishAck(rewardEventId, "FAILED", "Account not found: " + accountId);
                return;
            }
            Account account = accountOpt.get();

            // ── 5. Credit the reward to account balance ────────────────────────
            BigDecimal balanceBefore = account.getBalance();
            account.setBalance(balanceBefore.add(amount));
            accountRepository.save(account);

            log.info("[REWARD] ✅ Credited {} {} to accountId={} | balance: {} → {}",
                amount, currency, accountId, balanceBefore, account.getBalance());

            // ── 6. Save a DEPOSIT transaction so mobile history shows the reward ─
            // Use rewardEventId as idempotencyKey to prevent duplicate processing
            // Generate transaction_reference (required field)
            String transactionRef = "REWARD-" + rewardEventId.substring(0, 8).toUpperCase();
            
            Transaction rewardTx = Transaction.builder()
                .transactionType(TransactionType.DEPOSIT)
                .amount(amount)
                .fromAccount(null)      // system credit — no sender account
                .toAccount(account)
                .timestamp(LocalDateTime.now())
                .status(TransactionStatus.SUCCESS)
                .note(description)
                .idempotencyKey(rewardEventId)
                .transactionReference(transactionRef)
                .build();
            transactionRepository.save(rewardTx);

            log.info("[REWARD] Transaction record saved: txId={} for accountId={}", rewardTx.getId(), accountId);

            // ── 7. Publish ACK → promotions service updates status to DISBURSED ──
            publishAck(rewardEventId, "SUCCESS", null);

            // ── 8. Publish notification event → notification service tells user about the bonus ──
            // Stagger by 2.5s so the iOS UI displays the deposit banner before the bonus banner arrives
            try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
            publishRewardNotification(rewardEventId, accountId, amount, account.getBalance(), description, currency);

        } catch (Exception e) {
            log.error("[REWARD] Failed to process reward event: key={}, error={}", record.key(), e.getMessage(), e);
            // Don't throw — let Kafka move on. Failed events will stay DISPATCHED
            // in promotions DB and can be investigated.
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void publishAck(String rewardEventId, String status, String errorMessage) {
        try {
            Map<String, Object> ack = new HashMap<>();
            ack.put("rewardEventId", rewardEventId);
            ack.put("status", status);
            if (errorMessage != null) {
                ack.put("error", errorMessage);
            }
            kafkaTemplate.send(ACK_TOPIC, rewardEventId, ack);
            log.debug("[REWARD] ACK published: eventId={}, status={}", rewardEventId, status);
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

    /**
     * Publish a notification event to tell the user they received a reward bonus.
     * The notification service picks this up and sends an in-app + push notification.
     * Sends a Map object — same pattern as OutboxRelayService so the JSON serializer
     * produces the correct format the notification service expects.
     *
     * @param newBalance  the account balance AFTER the reward has been credited
     */
    private void publishRewardNotification(String rewardEventId, Long accountId, BigDecimal amount,
                                            BigDecimal newBalance, String description, String currency) {
        try {
            Optional<Account> accountOpt = accountRepository.findById(accountId);
            if (accountOpt.isEmpty()) {
                log.warn("[REWARD] Cannot publish notification — account {} not found", accountId);
                return;
            }

            Account account = accountOpt.get();
            String username = account.getUser() != null ? account.getUser().getUsername() : "Customer";
            String accountNumber = account.getAccountNumber();

            Map<String, String> metadata = new HashMap<>();
            metadata.put("accountId", accountId.toString());
            metadata.put("rewardType", "DEPOSIT_BONUS");
            metadata.put("receiverName", username);
            metadata.put("receiverAccount", accountNumber);
            // Include the new balance so NotificationService can display it in the message
            metadata.put("newBalance", newBalance != null ? newBalance.toPlainString() : "");
            metadata.put("bonusAmount", amount != null ? amount.toPlainString() : "");

            Map<String, Object> event = new HashMap<>();
            event.put("eventId", rewardEventId);
            event.put("eventType", "TransactionCompleted");
            event.put("eventVersion", "1.0");
            event.put("timestamp", java.time.Instant.now().toString());
            event.put("correlationId", rewardEventId);
            event.put("transactionId", rewardEventId);
            event.put("amount", amount);
            event.put("currency", currency);
            event.put("type", "PROMOTION");
            event.put("status", "SUCCESS");
            event.put("targetAccountNumber", accountNumber);
            event.put("username", username);
            event.put("note", description);
            event.put("metadata", metadata);

            // Send Map object — KafkaTemplate<String,Object> serializes it to JSON
            // matching the format OutboxRelayService produces
            kafkaTemplate.send("banking.transactions.completed", rewardEventId, event);

            log.info("[REWARD] 📢 Published notification event for accountId={} username={} amount={} newBalance={}",
                    accountId, username, amount, newBalance);

        } catch (Exception e) {
            log.error("[REWARD] Failed to publish notification for eventId={}: {}",
                    rewardEventId, e.getMessage());
        }
    }
}
