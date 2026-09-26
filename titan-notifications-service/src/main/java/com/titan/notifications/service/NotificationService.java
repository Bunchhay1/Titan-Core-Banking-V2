package com.titan.notifications.service;

import com.titan.notifications.event.TransactionCompletedEvent;
import com.titan.notifications.model.UserPreference;
import com.titan.notifications.strategy.ProviderStrategyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * NotificationService — single entry point for all notification processing.
 *
 * Flow (Kafka):
 *   core-banking publishes TWO events per TRANSFER to banking.transactions.completed:
 *     1. type=TRANSFER          → username=senderUsername   (Account A)
 *     2. type=TRANSFER_RECEIVED → username=receiverUsername (Account B)
 *
 *   For DEPOSIT / WITHDRAWAL: one event only (type=DEPOSIT / WITHDRAWAL).
 *
 * Message format (ABA/ACLEDA banking style — matches iOS parseBannerContent):
 *
 *   TRANSFER (sender):
 *     "You transferred $10.00 to Vanda (Acc: ···890). Ref: TXN-28. 10/07/2026 12:30"
 *
 *   TRANSFER_RECEIVED (receiver):
 *     "You received $10.00 from Navatra (Acc: ···567). Ref: TXN-28. 10/07/2026 12:30"
 *
 *   DEPOSIT:
 *     "Deposit of $200.00 to account ···890 was successful. Ref: TXN-42. 10/07/2026 12:30"
 *
 *   WITHDRAWAL:
 *     "Withdrawal of $50.00 from account ···567 was successful. Ref: TXN-43. 10/07/2026 12:30"
 *
 * NOTE: iOS parseBannerContent checks msg.hasPrefix("you received") / "you transferred" /
 *       "deposit" / "withdrawal"  (case-insensitive via lowercased()).
 *       The messages above are intentionally designed to match those prefixes.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationService {

    private static final BigDecimal HIGH_VALUE_THRESHOLD = new BigDecimal("500.00");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    // Dedup TTL: 30 seconds — prevents double delivery if Kafka + HTTP fire simultaneously
    private static final Duration DEDUP_TTL = Duration.ofSeconds(30);

    private final ProviderStrategyService providerService;
    private final TemplateService templateService;
    private final UserPreferenceService preferenceService;
    private final RateLimiterService rateLimiter;
    private final AuditService auditService;
    private final BatchingService batchingService;
    private final WebSocketNotificationService webSocketService;
    private final PredictiveDeliveryService predictiveDelivery;
    private final ApnsPushService apnsPushService;

    // Optional — absent when Redis is unavailable (falls back to no dedup)
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    // =========================================================================
    // MAIN ENTRY POINT — called by NotificationListener (Kafka consumer)
    // =========================================================================
    public void sendNotifications(TransactionCompletedEvent event) {
        log.info("─".repeat(70));
        log.info("📨 Processing txId={} type={} amount={} user={}",
                event.getTransactionId(), event.getTransactionType(),
                event.getAmount(), event.getUsername());

        // ── 0. Dedup guard — prevent sending the same notification twice ───────
        // Key: notify:dedup:{userId}:{txId}:{type}  TTL: 30 s
        // Handles cases where multiple consumers (Kafka + HTTP) or
        // multiple service paths fire the same event within a short window.
        String txType0 = event.getTransactionType() != null
                ? event.getTransactionType().toUpperCase() : "UNKNOWN";
        String dedupKey = "notify:dedup:"
                + (event.getUsername() != null ? event.getUsername().trim() : "unknown")
                + ":" + (event.getTransactionId() != null ? event.getTransactionId() : "0")
                + ":" + txType0;

        if (redisTemplate != null) {
            Boolean isNew = redisTemplate.opsForValue()
                    .setIfAbsent(dedupKey, "1", DEDUP_TTL);
            if (!Boolean.TRUE.equals(isNew)) {
                log.warn("🔇 Duplicate notification suppressed — key={}", dedupKey);
                return;
            }
        }

        // ── 1. Resolve & trim the userId (owner of this event) ────────────────
        String userId = resolveUserId(event);
        String locale = event.getLocale() != null ? event.getLocale()
                : preferenceService.getPreferredLocale(userId);

        String txType = event.getTransactionType() != null
                ? event.getTransactionType().toUpperCase() : "UNKNOWN";

        // ── 2. Opt-out checks ─────────────────────────────────────────────────
        // PROMOTION type = deposit bonus reward — treat as transaction alert, not marketing
        if (!preferenceService.canSendTransactionAlert(userId)) {
            log.info("🚫 User {} disabled transaction alerts", userId);
            return;
        }

        // ── 3. Build the alert message (banking style) ────────────────────────
        AlertMessage alert = buildAlertMessage(event, userId, txType);
        log.info("📋 Alert for {}: title='{}' body='{}'", userId, alert.title(), alert.body());

        // ── 4. WebSocket real-time push (web dashboard) ───────────────────────
        Map<String, Object> wsPayload = buildWsPayload(event, alert);
        webSocketService.pushToUser(userId, "TRANSACTION_ALERT", wsPayload);

        // ── 5. IN_APP audit record (iOS polls GET /api/audit/user/{username}) ──
        //       ONE record per event — no duplicates.
        //       The message is already in "You transferred…" / "You received…" format
        //       so iOS parseBannerContent works correctly.
        boolean isHighValue = event.getAmount() != null
                && event.getAmount().compareTo(HIGH_VALUE_THRESHOLD) >= 0;

        auditService.logAttempt(
                event.getTransactionId(), userId,
                "IN_APP", userId, alert.body(),
                "internal", locale, isHighValue);

        // ── 6. iOS APNs push ──────────────────────────────────────────────────
        apnsPushService.pushToUser(userId, alert.title(), alert.body(), wsPayload);

        // ── 7. SMS / Email (only for high-value or TRANSFER_RECEIVED) ─────────
        boolean isReceiverSide = "TRANSFER_RECEIVED".equals(txType);

        if (isHighValue || isReceiverSide) {
            sendExternalChannels(event, userId, locale, alert);
        } else {
            // Low-value sender — schedule for optimal delivery time
            scheduleExternalChannels(event, userId, locale, alert);
        }

        log.info("✅ Done txId={} user={}", event.getTransactionId(), userId);
        log.info("─".repeat(70));
    }

    // =========================================================================
    // ALERT MESSAGE BUILDER — ABA / ACLEDA banking format
    //
    // IMPORTANT: The iOS parseBannerContent method detects notification type by
    // checking the START of record.message (after lowercased()):
    //   • "you received"   → Money Received banner
    //   • "you transferred"→ Transfer Sent banner
    //   • "deposit"        → Deposit banner
    //   • "withdrawal"     → Withdrawal banner
    //
    // All messages here MUST start with the correct prefix.
    // =========================================================================
    private AlertMessage buildAlertMessage(TransactionCompletedEvent event,
                                           String userId, String txType) {
        Map<String, String> meta = event.getMetadata() != null ? event.getMetadata() : Map.of();

        String amount   = fmtAmount(event.getAmount());
        String currency = event.getCurrency() != null ? event.getCurrency() : "USD";
        String ref      = shortRef(event.getTransactionId());
        String dateTime = LocalDateTime.now().format(DT_FMT);

        // Format amount as "$700.00" for USD, "KHR 2,800" for KHR, "€50.00" for EUR
        String displayAmount = formatDisplayAmount(event.getAmount(), currency);

        return switch (txType) {

            // ── Account A sent money ───────────────────────────────────────────
            // iOS parseBannerContent matches on "you transferred" prefix
            case "TRANSFER", "PAYMENT" -> {
                String toName = meta.getOrDefault("receiverName", "");
                String toAcct = meta.getOrDefault("receiverAccount",
                        event.getTargetAccountNumber() != null ? event.getTargetAccountNumber() : "");

                String body = buildTransferSentBody(displayAmount, toName, toAcct, ref, dateTime);
                yield new AlertMessage("Transaction Complete", body);
            }

            // ── Account B received money ───────────────────────────────────────
            // iOS parseBannerContent matches on "you received" prefix
            case "TRANSFER_RECEIVED" -> {
                String fromName = meta.getOrDefault("senderName", "");
                String fromAcct = meta.getOrDefault("senderAccount",
                        event.getSourceAccountNumber() != null ? event.getSourceAccountNumber() : "");

                String body = buildTransferReceivedBody(displayAmount, fromName, fromAcct, ref, dateTime);
                yield new AlertMessage("Money Received", body);
            }

            // ── Withdrawal ────────────────────────────────────────────────────
            // iOS parseBannerContent matches on "withdrawal" prefix
            case "WITHDRAWAL" -> {
                String fromAcct = event.getSourceAccountNumber() != null
                        ? maskAcct(event.getSourceAccountNumber()) : "";
                String body = String.format(
                        "Withdrawal of %s from account %s was successful. Ref: %s. %s",
                        displayAmount, fromAcct, ref, dateTime);
                yield new AlertMessage("Transaction Complete", body);
            }

            // ── Deposit ───────────────────────────────────────────────────────
            // iOS parseBannerContent matches on "deposit" prefix
            case "DEPOSIT" -> {
                String toAcct = event.getTargetAccountNumber() != null
                        ? maskAcct(event.getTargetAccountNumber()) : "";

                String body = String.format(
                        "Deposit of %s to account %s was successful. Ref: %s. %s",
                        displayAmount, toAcct, ref, dateTime);

                yield new AlertMessage("Transaction Complete", body);
            }


            // ── Promotion Reward ──────────────────────────────────────────────
            // IMPORTANT: Must NOT start with "you received" / "you transferred" /
            // "deposit" / "withdrawal" — those prefixes are reserved for real
            // transaction types. iOS notification center reads the audit message
            // text to decide the banner style.
            case "PROMOTION" -> {
                // Try to read newBalance and bonusAmount from metadata (set by RewardConsumer)
                String newBalanceStr = meta.get("newBalance");
                String bonusAmountStr = meta.get("bonusAmount");

                // Format bonus amount — fall back to the event amount if metadata key missing
                BigDecimal bonusBd = parseSafe(bonusAmountStr, event.getAmount());
                String bonusDisplay = formatDisplayAmount(bonusBd, currency);

                String body;
                if (newBalanceStr != null && !newBalanceStr.isBlank()) {
                    BigDecimal newBalanceBd = parseSafe(newBalanceStr, null);
                    String balanceDisplay = formatDisplayAmount(newBalanceBd, currency);
                    // Start with "Bonus:" so iOS matches the bonus prefix
                    body = String.format(
                            "Bonus: %s reward added to your account. " +
                            "New balance: %s",
                            bonusDisplay, balanceDisplay);
                } else {
                    body = String.format(
                            "Bonus: %s reward has been added to your account.",
                            bonusDisplay);
                }
                yield new AlertMessage("Promotion Reward", body);
            }
            default -> {
                String body = String.format("%s of %s completed. Ref: %s. %s",
                        txType, displayAmount, ref, dateTime);
                yield new AlertMessage("Transaction Complete", body);
            }
        };
    }

    // ─── Transfer sent body (Account A) ──────────────────────────────────────
    // Format: "You transferred $10.00 to Vanda (Acc: ···890). Ref: TXN-28. 10/07/2026 12:30"
    // iOS checks msg.lowercased().hasPrefix("you transferred")
    private String buildTransferSentBody(String displayAmount, String toName,
                                         String toAcct, String ref, String dateTime) {
        StringBuilder sb = new StringBuilder("You transferred ");
        sb.append(displayAmount);
        if (!toName.isBlank()) {
            sb.append(" to ").append(toName);
        }
        if (!toAcct.isBlank()) {
            sb.append(" (Acc: ").append(maskAcct(toAcct)).append(")");
        }
        sb.append(". Ref: ").append(ref).append(". ").append(dateTime);
        return sb.toString();
    }

    // ─── Transfer received body (Account B) ──────────────────────────────────
    // Format: "You received $10.00 from Navatra (Acc: ···567). Ref: TXN-28. 10/07/2026 12:30"
    // iOS checks msg.lowercased().hasPrefix("you received")
    private String buildTransferReceivedBody(String displayAmount, String fromName,
                                             String fromAcct, String ref, String dateTime) {
        StringBuilder sb = new StringBuilder("You received ");
        sb.append(displayAmount);
        if (!fromName.isBlank()) {
            sb.append(" from ").append(fromName);
        }
        if (!fromAcct.isBlank()) {
            sb.append(" (Acc: ").append(maskAcct(fromAcct)).append(")");
        }
        sb.append(". Ref: ").append(ref).append(". ").append(dateTime);
        return sb.toString();
    }

    // =========================================================================
    // EXTERNAL CHANNELS (SMS / EMAIL)
    // =========================================================================
    private void sendExternalChannels(TransactionCompletedEvent event,
                                      String userId, String locale,
                                      AlertMessage alert) {
        UserPreference pref = preferenceService.getPreferences(userId);
        String recipientEmail = resolveEmail(pref, event);
        String recipientPhone = resolvePhone(pref, event);

        if (recipientPhone == null && recipientEmail == null) {
            log.info("ℹ️ No email/phone for userId={} — skipping external channels", userId);
            return;
        }

        try {
            if (recipientPhone != null && rateLimiter.allowSms(userId)) {
                providerService.sendSms(recipientPhone, alert.body());
                auditService.logAttempt(event.getTransactionId(), userId, "SMS",
                        recipientPhone, alert.body(), "twilio", locale, true);
            }
            if (recipientEmail != null && rateLimiter.allowEmail(userId)) {
                Map<String, Object> data = buildTemplateData(event, alert);
                String emailBody = templateService.renderEmail("transaction_alert", data, locale);
                providerService.sendEmail(recipientEmail, emailBody);
                auditService.logAttempt(event.getTransactionId(), userId, "EMAIL",
                        recipientEmail, emailBody, "sendgrid", locale, true);
            }
        } catch (Exception e) {
            log.warn("⚠️ External channel failed for userId={}: {}", userId, e.getMessage());
            // Do NOT write a failure audit — it creates noise in the iOS notification list
        }
    }

    private void scheduleExternalChannels(TransactionCompletedEvent event,
                                          String userId, String locale,
                                          AlertMessage alert) {
        UserPreference pref = preferenceService.getPreferences(userId);
        String recipientEmail = resolveEmail(pref, event);
        String recipientPhone = resolvePhone(pref, event);

        if (recipientPhone == null && recipientEmail == null) {
            log.info("ℹ️ No email/phone for userId={} — skipping scheduled external channels", userId);
            return;
        }

        long optimalTime = System.currentTimeMillis() + (8L * 3600 * 1000);

        if (recipientPhone != null) {
            predictiveDelivery.scheduleOptimalDelivery(
                    userId, "SMS", recipientPhone, alert.body(), optimalTime);
        }
        if (recipientEmail != null) {
            try {
                Map<String, Object> data = buildTemplateData(event, alert);
                String emailBody = templateService.renderEmail("transaction_alert", data, locale);
                predictiveDelivery.scheduleOptimalDelivery(
                        userId, "EMAIL", recipientEmail, emailBody, optimalTime);
            } catch (Exception e) {
                log.warn("⚠️ Email template failed for userId={}: {}", userId, e.getMessage());
            }
        }
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    /**
     * Resolve and TRIM the userId (= username) this event belongs to.
     *
     * For TRANSFER_RECEIVED events the username field must be the RECEIVER's
     * username (e.g. "vanda"), not the sender's. If old core-banking code
     * accidentally set username to the sender, we recover using metadata:
     *   - metadata.receiverName  → receiver's username
     *   - metadata.receiverAccount → receiver's account number (last-resort)
     */
    private String resolveUserId(TransactionCompletedEvent event) {
        String txType = event.getType() != null ? event.getType().toUpperCase() : "";

        // ── For TRANSFER_RECEIVED, the userId MUST be the receiver ────────────
        if ("TRANSFER_RECEIVED".equals(txType) && event.getMetadata() != null) {
            String receiverName = event.getMetadata().get("receiverName");
            String senderName   = event.getMetadata().get("senderName");

            // If username is set and is NOT the sender, it's correct — use it
            if (event.getUsername() != null && !event.getUsername().isBlank()) {
                String candidate = event.getUsername().trim();
                boolean isSender = senderName != null && senderName.equalsIgnoreCase(candidate);
                if (!isSender && !"SYSTEM".equalsIgnoreCase(candidate)) {
                    return candidate;   // ✅ correct receiver username
                }
            }

            // username was sender's name (old bug) — fall back to receiverName from metadata
            if (receiverName != null && !receiverName.isBlank()) {
                log.warn("⚠️ TRANSFER_RECEIVED event has sender username='{}' — recovering receiver from metadata: '{}'",
                        event.getUsername(), receiverName);
                return receiverName.trim();
            }

            // Last resort — receiver account number
            if (event.getTargetAccountNumber() != null && !event.getTargetAccountNumber().isBlank()) {
                return event.getTargetAccountNumber().trim();
            }
        }

        // ── Standard path: use event.username ─────────────────────────────────
        if (event.getUsername() != null) {
            String trimmed = event.getUsername().trim();
            if (!trimmed.isBlank() && !"SYSTEM".equalsIgnoreCase(trimmed)) {
                return trimmed;
            }
        }

        // ── Fallback: source account number ───────────────────────────────────
        if (event.getSourceAccountNumber() != null && !event.getSourceAccountNumber().isBlank()) {
            return event.getSourceAccountNumber().trim();
        }

        return "UNKNOWN";
    }

    private String resolveEmail(UserPreference pref, TransactionCompletedEvent event) {
        if (pref.getEmail() != null && !pref.getEmail().isBlank()
                && !"user@example.com".equals(pref.getEmail())) {
            return pref.getEmail();
        }
        if (event.getMetadata() != null) {
            String metaEmail = event.getMetadata().get("userEmail");
            if (metaEmail != null && !metaEmail.isBlank()) return metaEmail;
        }
        return null;
    }

    private String resolvePhone(UserPreference pref, TransactionCompletedEvent event) {
        if (pref.getSmsNumber() != null && !pref.getSmsNumber().isBlank()
                && !"+1234567890".equals(pref.getSmsNumber())) {
            return pref.getSmsNumber();
        }
        if (event.getMetadata() != null) {
            String metaPhone = event.getMetadata().get("userPhone");
            if (metaPhone != null && !metaPhone.isBlank()) return metaPhone;
        }
        return null;
    }

    private Map<String, Object> buildWsPayload(TransactionCompletedEvent event, AlertMessage alert) {
        Map<String, Object> data = new HashMap<>();
        data.put("title",               alert.title());
        data.put("body",                alert.body());
        data.put("transactionId",       event.getTransactionId());
        data.put("transactionType",     event.getTransactionType());
        data.put("status",              event.getStatus());
        data.put("amount",              event.getAmount());
        data.put("currency",            event.getCurrency());
        data.put("sourceAccountNumber", event.getSourceAccountNumber());
        data.put("targetAccountNumber", event.getTargetAccountNumber());
        data.put("username",            event.getUsername() != null ? event.getUsername().trim() : "");
        data.put("timestamp",           LocalDateTime.now().format(DT_FMT));
        if (event.getMetadata() != null) {
            data.putAll(event.getMetadata());
        }
        return data;
    }

    private Map<String, Object> buildTemplateData(TransactionCompletedEvent event, AlertMessage alert) {
        Map<String, Object> data = buildWsPayload(event, alert);
        String trimmedUsername = event.getUsername() != null ? event.getUsername().trim() : "Customer";
        data.put("userName",      trimmedUsername);
        data.put("messagePrefix", alert.body());
        return data;
    }

    /**
     * Format amount with currency symbol:
     *   USD 700.00 → "$700.00"
     *   KHR 2800   → "KHR 2,800.00"
     *   EUR 50.00  → "€50.00"
     */
    private String formatDisplayAmount(BigDecimal amount, String currency) {
        if (amount == null) return "$0.00";
        return switch (currency.toUpperCase()) {
            case "USD" -> String.format(java.util.Locale.US, "$%,.2f",    amount.doubleValue());
            case "EUR" -> String.format(java.util.Locale.US, "€%,.2f",    amount.doubleValue());
            case "KHR" -> String.format(java.util.Locale.US, "KHR %,.0f", amount.doubleValue());
            default    -> String.format(java.util.Locale.US, "%s %,.2f", currency, amount.doubleValue());
        };
    }

    /** Format amount: 10.00 → "10.00" */
    private String fmtAmount(BigDecimal amount) {
        if (amount == null) return "0.00";
        return String.format("%.2f", amount.doubleValue());
    }

    /**
     * Short reference from transactionId.
     * "28-recv" → "TXN-28"   "28" → "TXN-28"
     */
    private String shortRef(String txId) {
        if (txId == null) return "N/A";
        String base = txId.replace("-recv", "").trim();
        return "TXN-" + base;
    }

    /**
     * Mask account number for privacy: show first 3 + last 4 digits with "···" separator.
     * "001202625586" → "···5586"
     * Shorter accounts pass through as-is.
     */
    private String maskAcct(String acct) {
        if (acct == null || acct.isBlank()) return "";
        String stripped = acct.trim();
        if (stripped.length() <= 6) return stripped;
        return "···" + stripped.substring(stripped.length() - 4);
    }

    /** Simple value object for alert title + body */
    private record AlertMessage(String title, String body) {}

    /**
     * Parse a String amount from metadata safely.
     * Returns {@code fallback} if the string is null, blank, or not parseable.
     */
    private BigDecimal parseSafe(String value, BigDecimal fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
