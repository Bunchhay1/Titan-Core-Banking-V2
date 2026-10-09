package com.titan.titancorebanking.service;

import com.titan.titancorebanking.dto.request.RiskCheckRequest;
import com.titan.titancorebanking.dto.response.RiskCheckResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

/**
 * Risk Engine Service using REST API.
 *
 * FAIL-SAFE STRATEGY:
 * When the Python Risk Engine is offline or unreachable, this service implements
 * a configurable fail-safe strategy to balance security and availability:
 *
 * - BLOCK: Maximum security - block all transactions when AI is down (recommended for production)
 * - ALLOW: Maximum availability - allow all transactions when AI is down (use only in dev/test)
 * - MANUAL_REVIEW: Flag for manual review (requires human intervention workflow)
 *
 * Default: BLOCK (fail-safe, not fail-open)
 *
 * BUG FIX: Previously the code said "ALLOW" in comments but returned "BLOCK", causing confusion.
 * Now behavior is explicit and configurable.
 */
@Service
public class RiskEngineService {

    private static final Logger logger = LoggerFactory.getLogger(RiskEngineService.class);
    private final RestClient restClient;
    private final String riskEngineUrl;
    private final String failSafeAction;

    /**
     * Constructor Injection with configurable fail-safe strategy.
     *
     * @param builder RestClient builder
     * @param riskEngineUrl URL of the Python Risk Engine (default: http://localhost:8082)
     * @param failSafeAction Action to take when Risk Engine is offline: BLOCK (default), ALLOW, or MANUAL_REVIEW
     */
    public RiskEngineService(RestClient.Builder builder,
                             @Value("${risk.engine.url:http://localhost:8082}") String riskEngineUrl,
                             @Value("${risk.engine.failsafe.action:BLOCK}") String failSafeAction) {
        this.restClient = builder.build();
        this.riskEngineUrl = riskEngineUrl;
        this.failSafeAction = failSafeAction.toUpperCase();

        logger.info("RiskEngineService initialized with URL: {} and fail-safe action: {}",
                riskEngineUrl, this.failSafeAction);

        // Validate fail-safe action
        if (!this.failSafeAction.matches("BLOCK|ALLOW|MANUAL_REVIEW")) {
            logger.warn("Invalid fail-safe action '{}'. Defaulting to BLOCK for security.", this.failSafeAction);
        }
    }

    /**
     * Analyze transaction risk by calling Python Risk Engine.
     *
     * @param username User initiating the transaction
     * @param amount Transaction amount
     * @return Risk assessment with action (ALLOW, BLOCK, or MANUAL_REVIEW)
     */
    public RiskCheckResponse analyzeTransaction(String username, BigDecimal amount) {
        RiskCheckRequest request = new RiskCheckRequest(username, amount);

        logger.info("🤖 AI Risk Check: Asking Python Engine for user: {}, amount: {}", username, amount);

        try {
            // 📞 Call Python AI Risk Engine (POST http://localhost:8082/check-risk)
            RiskCheckResponse response = restClient.post()
                    .uri(riskEngineUrl + "/check-risk")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(RiskCheckResponse.class);

            logger.info("🤖 AI Verdict: {}", response);
            return response;

        } catch (Exception e) {
            // ✅ FIX: Implement explicit fail-safe strategy instead of misleading comments
            logger.error("⚠️ Risk Engine is OFFLINE or Error: {}. Applying fail-safe action: {}",
                    e.getMessage(), failSafeAction);

            return applyFailSafeStrategy(username, amount);
        }
    }

    /**
     * Apply configured fail-safe strategy when Risk Engine is unavailable.
     *
     * @param username User initiating the transaction
     * @param amount Transaction amount
     * @return Fail-safe risk assessment
     */
    private RiskCheckResponse applyFailSafeStrategy(String username, BigDecimal amount) {
        return switch (failSafeAction) {
            case "ALLOW" -> {
                // ⚠️ FAIL-OPEN: Allow transactions to proceed (availability over security)
                // WARNING: Use only in development/testing. Not recommended for production.
                logger.warn("🟢 FAIL-OPEN: Allowing transaction for user: {} despite Risk Engine offline", username);
                yield new RiskCheckResponse("UNKNOWN", "ALLOW");
            }
            case "MANUAL_REVIEW" -> {
                // ⚖️ Flag for manual review (requires human intervention)
                logger.warn("🟡 MANUAL_REVIEW: Transaction for user: {} flagged for manual review", username);
                yield new RiskCheckResponse("UNKNOWN", "MANUAL_REVIEW");
            }
            default -> {
                // 🔒 FAIL-SAFE (BLOCK): Block transactions when AI is unavailable (security over availability)
                // This is the RECOMMENDED production setting - prevents potentially fraudulent transactions
                // when the AI system cannot make an informed decision.
                logger.warn("🔴 FAIL-SAFE: Blocking transaction for user: {} due to Risk Engine offline", username);
                yield new RiskCheckResponse("UNKNOWN", "BLOCK");
            }
        };
    }
}