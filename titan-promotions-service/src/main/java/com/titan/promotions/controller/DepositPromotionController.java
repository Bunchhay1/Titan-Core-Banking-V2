package com.titan.promotions.controller;

import com.titan.promotions.event.TransactionCompletedEvent;
import com.titan.promotions.service.DepositPromotionService;
import com.titan.promotions.service.DepositPromotionService.CampaignStatusDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DepositPromotionController
 *
 * REST endpoints for the "Deposit $100 → Bonus $2" promotion campaign.
 *
 * Base path  : /promotions/deposit
 * Endpoints  :
 *   POST /promotions/deposit/apply     – trigger deposit promotion evaluation (writes to DB)
 *   POST /promotions/deposit/simulate  – dry-run: check eligibility without writing anything
 *   GET  /promotions/deposit/status    – check campaign status and window info
 */
@RestController
@RequestMapping("/promotions/deposit")
@Slf4j
@RequiredArgsConstructor
public class DepositPromotionController {

    private final DepositPromotionService depositPromotionService;

    // ─────────────────────────────────────────────────────────────────────
    // POST /promotions/deposit/apply
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Manually evaluate and apply the deposit promotion for a given transaction.
     *
     * Request body example:
     * {
     *   "transactionId": "1001",
     *   "type": "DEPOSIT",
     *   "amount": 150.00,
     *   "currency": "USD",
     *   "metadata": { "accountId": "42" }
     * }
     *
     * Response:
     *   200 OK   → { "applied": true,  "bonus": 2.00, "message": "..." }
     *   200 OK   → { "applied": false, "bonus": 0,    "message": "..." }
     *   400 Bad Request → when required fields are missing
     */
    @PostMapping("/apply")
    public ResponseEntity<Map<String, Object>> applyDepositPromotion(
            @RequestBody TransactionCompletedEvent event) {

        // Basic input validation
        if (event == null) {
            return ResponseEntity.badRequest()
                    .body(errorResponse("Request body is required"));
        }
        if (event.getAmount() == null) {
            return ResponseEntity.badRequest()
                    .body(errorResponse("Field 'amount' is required"));
        }
        if (event.getTransactionId() == null || event.getTransactionId().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(errorResponse("Field 'transactionId' is required"));
        }

        log.info("[DEPOSIT_PROMO] Manual apply request: transactionId={}, amount={}",
                event.getTransactionId(), event.getAmount());

        boolean applied = depositPromotionService.evaluateAndApply(event);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("transactionId",   event.getTransactionId());
        response.put("depositAmount",   event.getAmount());
        response.put("applied",         applied);
        response.put("bonus",           applied ? DepositPromotionService.BONUS_AMOUNT : BigDecimal.ZERO);
        response.put("campaignCode",    DepositPromotionService.CAMPAIGN_CODE);
        response.put("message", applied
                ? String.format("$%.2f deposit bonus successfully applied to transaction %s",
                        DepositPromotionService.BONUS_AMOUNT, event.getTransactionId())
                : "Promotion not applied — check campaign window, transaction type, or amount threshold");

        return ResponseEntity.ok(response);
    }

    // ─────────────────────────────────────────────────────────────────────
    // POST /promotions/deposit/simulate
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Dry-run eligibility check — evaluates whether a deposit would receive the
     * $2 bonus WITHOUT writing anything to the database.
     *
     * Useful for:
     *  - Testing during development
     *  - Front-end "you'll get a bonus!" preview before the user submits
     *  - QA automation
     *
     * Request body example:
     * {
     *   "transactionId": "SIM-001",
     *   "type": "DEPOSIT",
     *   "amount": 150.00,
     *   "currency": "USD"
     * }
     *
     * Response:
     * {
     *   "eligible":      true,
     *   "bonus":         2.00,
     *   "campaignCode":  "DEPOSIT_BONUS_JUL_AUG_2026",
     *   "reason":        "Deposit qualifies: amount $150.00 >= threshold $100.00, campaign is ACTIVE",
     *   "withinWindow":  true,
     *   "minDeposit":    100.00,
     *   "campaignStart": "2026-07-23T06:59",
     *   "campaignEnd":   "2026-09-30T23:59:59"
     * }
     */
    @PostMapping("/simulate")
    public ResponseEntity<Map<String, Object>> simulateDepositPromotion(
            @RequestBody TransactionCompletedEvent event) {

        if (event == null || event.getAmount() == null) {
            return ResponseEntity.badRequest()
                    .body(errorResponse("Fields 'amount' and 'type' are required"));
        }

        boolean withinWindow = depositPromotionService.isWithinCampaignPeriod(
                java.time.LocalDateTime.now());
        boolean isDeposit = DepositPromotionService.TRANSACTION_TYPE
                .equalsIgnoreCase(event.getTransactionType());
        boolean meetsThreshold = event.getAmount()
                .compareTo(DepositPromotionService.MIN_QUALIFYING_DEPOSIT) >= 0;

        // Fetch campaign status from DB for realistic preview
        CampaignStatusDto campaignStatus = depositPromotionService.getCampaignStatus();
        boolean campaignActive = campaignStatus.isFound()
                && "ACTIVE".equals(campaignStatus.getDbStatus());

        boolean eligible = withinWindow && isDeposit && meetsThreshold && campaignActive;

        String reason;
        if (!campaignActive) {
            reason = "Campaign is not ACTIVE in the database";
        } else if (!withinWindow) {
            reason = String.format("Current time is outside campaign window [%s – %s]",
                    DepositPromotionService.CAMPAIGN_START,
                    DepositPromotionService.CAMPAIGN_END);
        } else if (!isDeposit) {
            reason = String.format("Transaction type '%s' is not DEPOSIT",
                    event.getTransactionType());
        } else if (!meetsThreshold) {
            reason = String.format("Amount $%.2f is below minimum qualifying deposit $%.2f",
                    event.getAmount(), DepositPromotionService.MIN_QUALIFYING_DEPOSIT);
        } else {
            reason = String.format("Deposit qualifies: amount $%.2f >= threshold $%.2f, campaign is ACTIVE",
                    event.getAmount(), DepositPromotionService.MIN_QUALIFYING_DEPOSIT);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("eligible",       eligible);
        response.put("bonus",          eligible ? DepositPromotionService.BONUS_AMOUNT : BigDecimal.ZERO);
        response.put("campaignCode",   DepositPromotionService.CAMPAIGN_CODE);
        response.put("reason",         reason);
        response.put("withinWindow",   withinWindow);
        response.put("minDeposit",     DepositPromotionService.MIN_QUALIFYING_DEPOSIT);
        response.put("campaignStart",  DepositPromotionService.CAMPAIGN_START.toString());
        response.put("campaignEnd",    DepositPromotionService.CAMPAIGN_END.toString());
        response.put("simulatedOnly",  true);

        log.info("[DEPOSIT_PROMO] Simulate request: amount={}, type={}, eligible={}",
                event.getAmount(), event.getTransactionType(), eligible);

        return ResponseEntity.ok(response);
    }

    // ─────────────────────────────────────────────────────────────────────
    // GET /promotions/deposit/status
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns the current status of the deposit promotion campaign.
     *
     * Response example:
     * {
     *   "campaignCode":  "DEPOSIT_BONUS_JUL_AUG_2026",
     *   "found":         true,
     *   "dbStatus":      "ACTIVE",
     *   "startDate":     "2026-07-23T06:59",
     *   "endDate":       "2026-08-23T23:59:59",
     *   "currentTime":   "2026-07-23T18:31:46",
     *   "withinWindow":  true,
     *   "active":        true,
     *   "minDeposit":    100.00,
     *   "bonusAmount":   2.00,
     *   "quotaUsed":     0,
     *   "quotaLimit":    null,
     *   "message":       "Campaign is ACTIVE and accepting deposits"
     * }
     */
    @GetMapping("/status")
    public ResponseEntity<CampaignStatusDto> getCampaignStatus() {
        log.debug("[DEPOSIT_PROMO] Status check requested");
        CampaignStatusDto status = depositPromotionService.getCampaignStatus();
        return ResponseEntity.ok(status);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────

    private Map<String, Object> errorResponse(String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", message);
        return err;
    }
}
