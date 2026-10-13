package com.titan.titancorebanking.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.titan.riskengine.RiskCheckRequest;
import com.titan.riskengine.RiskCheckResponse;
import com.titan.riskengine.RiskEngineServiceGrpc;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class RiskEngineGrpcService {

    private final RiskEngineServiceGrpc.RiskEngineServiceBlockingStub riskStub;

    @Value("${titan.ai.deadline-ms:100}")
    private long deadlineMs;

    @Value("${titan.ai.local.block.limit:100000}")
    private double localBlockLimit;

    public RiskEngineGrpcService(RiskEngineServiceGrpc.RiskEngineServiceBlockingStub riskStub) {
        this.riskStub = riskStub;
    }

    /**
     * Calls the external AI Risk Engine over gRPC before any DB locks are acquired.
     * Enforces a strict deadline and uses a Circuit Breaker to prevent cascading failures.
     */
    @CircuitBreaker(name = "risk-engine", fallbackMethod = "fallbackRiskCheck")
    public RiskCheckResponse analyzeTransaction(String userId, double amount) {
        log.debug("Calling AI Risk Engine | User: {} | Amount: ${}", userId, amount);

        RiskCheckRequest request = RiskCheckRequest.newBuilder()
                .setUserId(userId)
                .setAmount(amount)
                .build();

        // STAFF ENGINEER FIX: Hard deadline prevents thread exhaustion during network degradation
        return riskStub
                .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                .checkRisk(request);
    }

    /**
     * Fallback strategy executed automatically when Circuit Breaker opens or Deadline is exceeded.
     * Implements a "Fail-Open" policy for low amounts and a local heuristic block for high amounts.
     */
    public RiskCheckResponse fallbackRiskCheck(String userId, double amount, Throwable t) {
        log.warn("AI Service degraded or unreachable ({}). Applying local fallback rules for User: {}", t.getMessage(), userId);

        if (amount >= localBlockLimit) {
            log.warn("LOCAL HEURISTIC BLOCK | User: {} | Amount: ${} exceeds limit ${}", userId, amount, localBlockLimit);
            return RiskCheckResponse.newBuilder()
                    .setRiskScore(100)
                    .setRiskLevel("BLOCKED")
                    .setAction("BLOCK")
                    .build();
        }

        // Fail-Open for standard transactions to maintain system availability
        return RiskCheckResponse.newBuilder()
                .setRiskScore(0)
                .setRiskLevel("UNKNOWN_ALLOWED")
                .setAction("MANUAL_REVIEW")
                .build();
    }
}