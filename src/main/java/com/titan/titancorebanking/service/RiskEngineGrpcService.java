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

    // Hard limit enforced locally even when AI service is unreachable.
    @Value("${titan.ai.local.block.limit:100000}")
    private double localBlockLimit;

    public RiskEngineGrpcService(RiskEngineServiceGrpc.RiskEngineServiceBlockingStub riskStub) {
        this.riskStub = riskStub;
    }

    @CircuitBreaker(name = "risk-engine", fallbackMethod = "fallbackRiskCheck")
    public RiskCheckResponse analyzeTransaction(String userId, double amount) {
        log.debug("Calling AI Risk Engine | User: {} | Amount: ${}", userId, amount);

        RiskCheckRequest request = RiskCheckRequest.newBuilder()
                .setUserId(userId)
                .setAmount(amount)
                .build();

        return riskStub
                .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                .checkRisk(request);
    }

    // Fallback: AI unreachable → enforce block limit locally.
    public RiskCheckResponse fallbackRiskCheck(String userId, double amount, Throwable t) {
        log.debug("AI Service unreachable: {}. Using local fallback.", t.getMessage());

        if (amount >= localBlockLimit) {
            log.warn("🚫 LOCAL BLOCK | User: {} | Amount: ${} >= limit ${}", userId, amount, localBlockLimit);
            return RiskCheckResponse.newBuilder()
                    .setRiskScore(100)
                    .setRiskLevel("BLOCKED")
                    .setAction("BLOCK")
                    .build();
        }

        return RiskCheckResponse.newBuilder()
                .setRiskScore(0)
                .setRiskLevel("UNKNOWN")
                .setAction("MANUAL_REVIEW")
                .build();
    }
}
