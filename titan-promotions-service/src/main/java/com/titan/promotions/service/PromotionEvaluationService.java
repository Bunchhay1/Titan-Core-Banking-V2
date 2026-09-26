package com.titan.promotions.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.titan.promotions.cache.CampaignCacheService;
import com.titan.promotions.engine.RuleEngine;
import com.titan.promotions.event.RewardGrantedEvent;
import com.titan.promotions.event.TransactionCompletedEvent;
import com.titan.promotions.idempotency.IdempotencyService;
import com.titan.promotions.lock.DistributedLockService;
import com.titan.promotions.model.AppliedPromotion;
import com.titan.promotions.model.Campaign;
import com.titan.promotions.model.PromotionOutbox;
import com.titan.promotions.repository.AppliedPromotionRepository;
import com.titan.promotions.repository.CampaignRepository;
import com.titan.promotions.repository.PromotionOutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class PromotionEvaluationService {

    private final CampaignCacheService cacheService;
    private final RuleEngine ruleEngine;
    private final IdempotencyService idempotencyService;
    private final DistributedLockService lockService;
    private final CampaignRepository campaignRepository;
    private final AppliedPromotionRepository appliedPromotionRepository;
    private final PromotionOutboxRepository outboxRepository;
    private final DepositPromotionService depositPromotionService;
    private final ObjectMapper objectMapper;

    private final Timer evaluationTimer;
    private final Counter promotionsAppliedCounter;
    private final Counter duplicateEventsCounter;

    public PromotionEvaluationService(
            CampaignCacheService cacheService,
            RuleEngine ruleEngine,
            IdempotencyService idempotencyService,
            DistributedLockService lockService,
            CampaignRepository campaignRepository,
            AppliedPromotionRepository appliedPromotionRepository,
            PromotionOutboxRepository outboxRepository,
            DepositPromotionService depositPromotionService,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.cacheService = cacheService;
        this.ruleEngine = ruleEngine;
        this.idempotencyService = idempotencyService;
        this.lockService = lockService;
        this.campaignRepository = campaignRepository;
        this.appliedPromotionRepository = appliedPromotionRepository;
        this.outboxRepository = outboxRepository;
        this.depositPromotionService = depositPromotionService;
        this.objectMapper = objectMapper;

        this.evaluationTimer = Timer.builder("promotion.evaluation.time")
            .description("Time taken to evaluate promotions")
            .register(meterRegistry);
        this.promotionsAppliedCounter = Counter.builder("promotion.applied.total")
            .description("Total promotions applied")
            .register(meterRegistry);
        this.duplicateEventsCounter = Counter.builder("promotion.duplicate.events")
            .description("Duplicate transaction events received")
            .register(meterRegistry);
    }

    @Transactional("transactionManager")
    public void evaluateTransaction(TransactionCompletedEvent event) {
        evaluationTimer.record(() -> {
            log.debug("[EVAL] Starting evaluation for transactionId={}, type={}, amount={}",
                event.getTransactionId(), event.getTransactionType(), event.getAmount());

            // ── Idempotency guard ────────────────────────────────────────────────
            if (!idempotencyService.markAsProcessed(parseTransactionId(event.getTransactionId()))) {
                duplicateEventsCounter.increment();
                log.warn("[EVAL] Duplicate transaction {} — skipping", event.getTransactionId());
                return;
            }

            // ── Deposit Bonus: DEPOSIT_BONUS_JUL_AUG_2026 ───────────────────────
            // Explicitly call DepositPromotionService so that the $2 bonus is applied
            // even when the generic rule-engine campaign cache has not yet loaded the campaign.
            log.debug("[EVAL] Invoking DepositPromotionService for transactionId={}", event.getTransactionId());
            boolean depositBonusApplied = depositPromotionService.evaluateAndApply(event);
            log.info("[EVAL] DepositBonus result: applied={} for transactionId={}", depositBonusApplied, event.getTransactionId());

            // ── Generic rule-engine: all other active campaigns in DB ────────────
            List<Campaign> campaigns = cacheService.getActiveCampaigns();
            log.debug("[EVAL] Found {} active campaign(s) in cache for transactionId={}",
                campaigns.size(), event.getTransactionId());

            for (Campaign campaign : campaigns) {
                // Skip DEPOSIT_BONUS_JUL_AUG_2026 and legacy 4444 — already handled above to avoid double award
                if (DepositPromotionService.CAMPAIGN_CODE.equals(campaign.getCampaignCode()) || "4444".equals(campaign.getCampaignCode())) {
                    log.debug("[EVAL] Skipping campaign '{}' (already handled by DepositPromotionService)",
                        campaign.getCampaignCode());
                    continue;
                }

                log.debug("[EVAL] Evaluating campaign '{}' rule='{}' for transactionId={}",
                    campaign.getCampaignCode(), campaign.getRuleExpression(), event.getTransactionId());

                if (ruleEngine.evaluate(campaign.getRuleExpression(), event)) {
                    log.info("[EVAL] ✅ Campaign '{}' matched for transactionId={}",
                        campaign.getCampaignCode(), event.getTransactionId());
                    applyPromotion(campaign, event);
                } else {
                    log.debug("[EVAL] Campaign '{}' did not match for transactionId={}",
                        campaign.getCampaignCode(), event.getTransactionId());
                }
            }
        });
    }

    private void applyPromotion(Campaign campaign, TransactionCompletedEvent event) {
        lockService.executeWithLock(campaign.getId(), () -> {
            Campaign locked = campaignRepository.findById(campaign.getId())
                .orElseThrow(() -> new RuntimeException("Campaign not found: " + campaign.getId()));

            if (locked.getQuotaLimit() != null && locked.getQuotaUsed() >= locked.getQuotaLimit()) {
                log.info("[EVAL] Campaign '{}' quota exhausted ({}/{})",
                    campaign.getCampaignCode(), locked.getQuotaUsed(), locked.getQuotaLimit());
                return null;
            }

            Long resolvedAccountId = event.getAccountId();
            if (resolvedAccountId == null) {
                log.warn("[EVAL] accountId not in metadata for tx={}, campaign='{}'; defaulting to 0",
                    event.getTransactionId(), campaign.getCampaignCode());
                resolvedAccountId = 0L;
            }

            // ── Per-account one-time guard ───────────────────────────────────
            // Campaigns with a quota_limit are intended as one-time-per-account rewards
            // (e.g. FIRST_1000 = each of first 1000 users gets it once).
            // Skip if this account already received this campaign.
            if (locked.getQuotaLimit() != null && resolvedAccountId != 0L) {
                boolean alreadyReceived = appliedPromotionRepository
                    .existsByAccountIdAndPromotionType(resolvedAccountId, campaign.getCampaignCode());
                if (alreadyReceived) {
                    log.info("[EVAL] Account {} already received campaign '{}' — skipping (one-time reward)",
                        resolvedAccountId, campaign.getCampaignCode());
                    return null;
                }
            }

            AppliedPromotion applied = AppliedPromotion.builder()
                .transactionId(parseTransactionId(event.getTransactionId()))
                .accountId(resolvedAccountId)
                .campaignId(campaign.getId())           // ← always set campaignId
                .promotionType(campaign.getCampaignCode())
                .promotionAmount(campaign.getRewardAmount())
                .appliedAt(LocalDateTime.now())
                .description(campaign.getName())
                .rewardStatus(AppliedPromotion.RewardStatus.PENDING)
                .build();

            appliedPromotionRepository.save(applied);

            locked.setQuotaUsed(locked.getQuotaUsed() + 1);
            locked.setUpdatedAt(LocalDateTime.now());
            campaignRepository.save(locked);

            createRewardOutboxEvent(applied, event);

            promotionsAppliedCounter.increment();
            log.info("[EVAL] ✅ Applied campaign '{}' | amount={} | accountId={} | transactionId={}",
                campaign.getCampaignCode(), campaign.getRewardAmount(),
                resolvedAccountId, event.getTransactionId());

            return null;
        });
    }

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

            applied.setRewardEventId(rewardEvent.getEventId());
            applied.setRewardStatus(AppliedPromotion.RewardStatus.DISPATCHED);
            appliedPromotionRepository.save(applied);

            log.debug("[EVAL] Outbox event created: eventId={} for promotionId={}",
                rewardEvent.getEventId(), applied.getId());

        } catch (Exception e) {
            log.error("[EVAL] Failed to create outbox event for promotionId={}", applied.getId(), e);
            throw new RuntimeException("Outbox creation failed", e);
        }
    }

    /**
     * Parse a transaction ID string like "28" or "28-recv" → Long.
     */
    private Long parseTransactionId(String transactionId) {
        if (transactionId == null || transactionId.isBlank()) return 0L;
        try {
            return Long.parseLong(transactionId.split("-")[0]);
        } catch (NumberFormatException e) {
            log.warn("[EVAL] Cannot parse transactionId '{}' as Long, using 0", transactionId);
            return 0L;
        }
    }
}
