package com.titan.promotions.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.titan.promotions.event.RewardGrantedEvent;
import com.titan.promotions.event.TransactionCompletedEvent;
import com.titan.promotions.model.AppliedPromotion;
import com.titan.promotions.model.Campaign;
import com.titan.promotions.model.PromotionOutbox;
import com.titan.promotions.repository.AppliedPromotionRepository;
import com.titan.promotions.repository.CampaignRepository;
import com.titan.promotions.repository.PromotionOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * DepositPromotionService
 *
 * Handles the "Deposit ≥ $100 → Bonus $2" promotion campaign.
 *
 * Campaign window  : 2026-07-23 06:59:00  →  2026-09-30 23:59:59
 * Trigger rule     : transactionType == DEPOSIT  AND  amount >= 100.00
 * Reward           : $2.00 flat bonus
 * Campaign code    : DEPOSIT_BONUS_JUL_AUG_2026
 *
 * Enhancement (2026-08-29):
 *  - Extended campaign end date to 2026-09-30 23:59:59 (see V9 migration).
 *  - Added idempotency guard: a given transactionId can only receive the
 *    $2 bonus once. Duplicate Kafka / REST calls for the same transaction
 *    are detected and silently skipped (idempotent — returns false, no error).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DepositPromotionService {

    // ── Constants ────────────────────────────────────────────────────────
    public static final String CAMPAIGN_CODE        = "DEPOSIT_BONUS_JUL_AUG_2026";
    /** Deposits AT OR ABOVE this threshold qualify for the bonus (inclusive lower bound). */
    public static final BigDecimal MIN_QUALIFYING_DEPOSIT = new BigDecimal("100.00");
    /** @deprecated Use MIN_QUALIFYING_DEPOSIT. Kept for backward compatibility. */
    @Deprecated
    public static final BigDecimal MAX_DEPOSIT      = MIN_QUALIFYING_DEPOSIT;
    public static final BigDecimal BONUS_AMOUNT     = new BigDecimal("2.00");
    public static final String TRANSACTION_TYPE     = "DEPOSIT";

    // Campaign period (inclusive on both ends)
    // V9 migration extended the end date from 2026-08-23 → 2026-09-30
    public static final LocalDateTime CAMPAIGN_START =
            LocalDateTime.of(2026, 7, 23,  6, 59, 0);
    public static final LocalDateTime CAMPAIGN_END   =
            LocalDateTime.of(2026, 9, 30, 23, 59, 59);

    // ── Dependencies ─────────────────────────────────────────────────────
    private final CampaignRepository          campaignRepository;
    private final AppliedPromotionRepository  appliedPromotionRepository;
    private final PromotionOutboxRepository   outboxRepository;
    private final ObjectMapper                objectMapper;

    // ─────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Evaluate whether the incoming deposit event qualifies for the $2 bonus.
     *
     * Checks performed (in order):
     *  1. Campaign exists and is still ACTIVE in DB.
     *  2. Current wall-clock time is within [CAMPAIGN_START, CAMPAIGN_END].
     *  3. Transaction type is DEPOSIT (case-insensitive).
     *  4. Deposit amount ≥ $100.
     *
     * @param event   incoming transaction event from core-banking
     * @return        true if the bonus was applied, false otherwise
     */
    @Transactional("transactionManager")
    public boolean evaluateAndApply(TransactionCompletedEvent event) {
        log.debug("DepositPromotionService evaluating transaction={}", event.getTransactionId());

        // 1. Validate basic event fields
        if (event == null || event.getAmount() == null || event.getTransactionId() == null) {
            log.warn("[DEPOSIT_PROMO] Skipping: event or required fields are null");
            return false;
        }

        // 2. Check campaign exists and is active
        Optional<Campaign> campaignOpt = campaignRepository.findByCampaignCode(CAMPAIGN_CODE);
        if (campaignOpt.isEmpty()) {
            log.warn("[DEPOSIT_PROMO] Campaign '{}' not found in DB — skipping", CAMPAIGN_CODE);
            return false;
        }
        Campaign campaign = campaignOpt.get();
        if (campaign.getStatus() != Campaign.CampaignStatus.ACTIVE) {
            log.info("[DEPOSIT_PROMO] Campaign '{}' is not ACTIVE (status={}), skipping",
                    CAMPAIGN_CODE, campaign.getStatus());
            return false;
        }

        // 3. Validate campaign date window
        LocalDateTime now = LocalDateTime.now();
        if (!isWithinCampaignPeriod(now)) {
            log.info("[DEPOSIT_PROMO] Outside campaign window [{} – {}]. Current time: {}",
                    CAMPAIGN_START, CAMPAIGN_END, now);
            return false;
        }

        // 4. Check transaction type = DEPOSIT
        String txType = event.getTransactionType();
        if (!TRANSACTION_TYPE.equalsIgnoreCase(txType)) {
            log.debug("[DEPOSIT_PROMO] Skipping: transactionType='{}' is not DEPOSIT", txType);
            return false;
        }

        // 5. Check deposit amount based on currency
        BigDecimal minDeposit = MAX_DEPOSIT;
        BigDecimal bonusAmount = BONUS_AMOUNT;
        
        if ("KHR".equalsIgnoreCase(event.getCurrency())) {
            minDeposit = new BigDecimal("400000.00");
            bonusAmount = new BigDecimal("8000.00");
        } else if ("EUR".equalsIgnoreCase(event.getCurrency())) {
            minDeposit = new BigDecimal("90.00");
            bonusAmount = new BigDecimal("1.80");
        }

        if (event.getAmount().compareTo(minDeposit) < 0) {
            log.debug("[DEPOSIT_PROMO] Skipping: amount={} < minQualifyingDeposit={} (must be >= threshold to qualify)",
                    event.getAmount(), minDeposit);
            return false;
        }

        // 6. Idempotency guard — prevent double-bonus for the same transactionId
        Long parsedTxId = parseTransactionId(event.getTransactionId());
        boolean alreadyApplied = appliedPromotionRepository
                .existsByTransactionIdAndPromotionType(parsedTxId, CAMPAIGN_CODE);
        if (alreadyApplied) {
            log.info("[DEPOSIT_PROMO] Skipping duplicate: bonus already applied for transactionId={}",
                    event.getTransactionId());
            return false;
        }

        // 7. All checks passed — apply bonus
        return applyBonus(campaign, event, bonusAmount);
    }

    /**
     * Returns true when the given timestamp falls inside the campaign window.
     */
    public boolean isWithinCampaignPeriod(LocalDateTime dateTime) {
        return !dateTime.isBefore(CAMPAIGN_START) && !dateTime.isAfter(CAMPAIGN_END);
    }

    /**
     * Returns a human-readable status snapshot of the campaign.
     * Used by the REST endpoint for monitoring.
     */
    public CampaignStatusDto getCampaignStatus() {
        Optional<Campaign> opt = campaignRepository.findByCampaignCode(CAMPAIGN_CODE);
        LocalDateTime now = LocalDateTime.now();

        if (opt.isEmpty()) {
            return CampaignStatusDto.builder()
                    .campaignCode(CAMPAIGN_CODE)
                    .found(false)
                    .message("Campaign not found in database")
                    .build();
        }

        Campaign c = opt.get();
        boolean active = c.getStatus() == Campaign.CampaignStatus.ACTIVE
                && isWithinCampaignPeriod(now);

        return CampaignStatusDto.builder()
                .campaignCode(CAMPAIGN_CODE)
                .found(true)
                .dbStatus(c.getStatus().name())
                .startDate(CAMPAIGN_START.toString())
                .endDate(CAMPAIGN_END.toString())
                .currentTime(now.toString())
                .withinWindow(isWithinCampaignPeriod(now))
                .active(active)
                .minDeposit(MIN_QUALIFYING_DEPOSIT)
                .bonusAmount(BONUS_AMOUNT)
                .quotaUsed(c.getQuotaUsed())
                .quotaLimit(c.getQuotaLimit())
                .message(active ? "Campaign is ACTIVE and accepting deposits" : "Campaign is NOT active")
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────

    private boolean applyBonus(Campaign campaign, TransactionCompletedEvent event, BigDecimal bonusAmount) {
        // Quota guard (if quotaLimit is set)
        if (campaign.getQuotaLimit() != null && campaign.getQuotaUsed() >= campaign.getQuotaLimit()) {
            log.info("[DEPOSIT_PROMO] Campaign '{}' quota exhausted ({}/{})",
                    CAMPAIGN_CODE, campaign.getQuotaUsed(), campaign.getQuotaLimit());
            return false;
        }

        Long accountId = resolveAccountId(event);
        Long transactionId = parseTransactionId(event.getTransactionId());

        // Build and persist the applied promotion record
        AppliedPromotion applied = AppliedPromotion.builder()
                .transactionId(transactionId)
                .accountId(accountId)
                .campaignId(campaign.getId())
                .promotionType(CAMPAIGN_CODE)
                .promotionAmount(bonusAmount)
                .appliedAt(LocalDateTime.now())
                .description(String.format(
                        "Deposit Bonus: %.2f %s reward for deposit of %.2f %s",
                        bonusAmount, event.getCurrency(), event.getAmount(), event.getCurrency()))
                .rewardStatus(AppliedPromotion.RewardStatus.PENDING)
                .build();

        appliedPromotionRepository.save(applied);

        // Increment quota usage
        campaign.setQuotaUsed(campaign.getQuotaUsed() + 1);
        campaign.setUpdatedAt(LocalDateTime.now());
        campaignRepository.save(campaign);

        // Create outbox event to dispatch reward to core-banking for crediting
        createRewardOutboxEvent(applied, event);

        log.info("[DEPOSIT_PROMO] ✅ Bonus {} {} applied | accountId={} | transactionId={} | depositAmount={} {}",
                bonusAmount, event.getCurrency(), accountId, event.getTransactionId(), event.getAmount(), event.getCurrency());

        return true;
    }

    /**
     * Create a REWARD_GRANTED outbox event to send the deposit bonus to core-banking.
     * This triggers the actual credit to the customer's account via the outbox processor.
     */
    private void createRewardOutboxEvent(AppliedPromotion applied, TransactionCompletedEvent event) {
        try {
            RewardGrantedEvent rewardEvent = RewardGrantedEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .eventType("REWARD_GRANTED")
                    .eventVersion("1.0")
                    .timestamp(LocalDateTime.now().toString())
                    .correlationId(event.getCorrelationId())
                    .accountId(applied.getAccountId())
                    .transactionId(applied.getTransactionId())
                    .campaignId(applied.getCampaignId())
                    .rewardAmount(applied.getPromotionAmount())
                    .currency(event.getCurrency())
                    .description(applied.getDescription())
                    .build();

            PromotionOutbox outbox = PromotionOutbox.builder()
                    .eventId(rewardEvent.getEventId())
                    .eventType("REWARD_GRANTED")
                    .payload(objectMapper.writeValueAsString(rewardEvent))
                    .status(PromotionOutbox.OutboxStatus.PENDING)
                    .createdAt(LocalDateTime.now())
                    .build();

            outboxRepository.save(outbox);

            // Update the applied promotion with event ID and DISPATCHED status
            applied.setRewardEventId(rewardEvent.getEventId());
            applied.setRewardStatus(AppliedPromotion.RewardStatus.DISPATCHED);
            appliedPromotionRepository.save(applied);

            log.debug("[DEPOSIT_PROMO] Outbox event created: eventId={} for promotionId={}",
                    rewardEvent.getEventId(), applied.getId());

        } catch (Exception e) {
            log.error("[DEPOSIT_PROMO] Failed to create outbox event for promotionId={}", applied.getId(), e);
            throw new RuntimeException("Outbox creation failed for deposit bonus", e);
        }
    }

    private Long resolveAccountId(TransactionCompletedEvent event) {
        Long accountId = event.getAccountId();
        if (accountId == null) {
            log.warn("[DEPOSIT_PROMO] accountId not found in metadata for tx={}, defaulting to 0",
                    event.getTransactionId());
            return 0L;
        }
        return accountId;
    }

    /**
     * Parse transactionId (may be "28" or "28-recv") → Long.
     */
    private Long parseTransactionId(String transactionId) {
        if (transactionId == null || transactionId.isBlank()) return 0L;
        try {
            return Long.parseLong(transactionId.split("-")[0]);
        } catch (NumberFormatException e) {
            log.warn("[DEPOSIT_PROMO] Cannot parse transactionId '{}' as Long, using 0", transactionId);
            return 0L;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Inner DTO
    // ─────────────────────────────────────────────────────────────────────

    @lombok.Builder
    @lombok.Data
    public static class CampaignStatusDto {
        private String     campaignCode;
        private boolean    found;
        private String     dbStatus;
        private String     startDate;
        private String     endDate;
        private String     currentTime;
        private boolean    withinWindow;
        private boolean    active;
        private BigDecimal minDeposit;
        private BigDecimal bonusAmount;
        private int        quotaUsed;
        private Integer    quotaLimit;
        private String     message;
    }
}
