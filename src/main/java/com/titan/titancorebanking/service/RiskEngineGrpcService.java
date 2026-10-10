package com.titan.titancorebanking.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import com.titan.riskengine.RiskCheckRequest;
import com.titan.riskengine.RiskCheckResponse;
import com.titan.riskengine.RiskEngineServiceGrpc;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class RiskEngineGrpcService {

    private final RiskEngineServiceGrpc.RiskEngineServiceBlockingStub riskStub;
    private final MeterRegistry meterRegistry;

    @Value("${titan.ai.deadline-ms:100}")
    private long deadlineMs;

    @Value("${titan.ai.local.block.limit:100000}")
    private BigDecimal localBlockLimit;

    private Timer riskCheckTimer;

    public RiskEngineGrpcService(
            RiskEngineServiceGrpc.RiskEngineServiceBlockingStub riskStub,
            MeterRegistry meterRegistry) {
        this.riskStub = Objects.requireNonNull(riskStub, "riskStub must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    @PostConstruct
    public void init() {
        // Fail-fast configuration validation (Architectural Integrity & Defensive Programming)
        Assert.isTrue(deadlineMs > 0, "Deadline milliseconds must be greater than 0");
        Assert.notNull(localBlockLimit, "Local block limit must not be null");
        Assert.isTrue(localBlockLimit.compareTo(BigDecimal.ZERO) >= 0, "Local block limit cannot be negative");

        // Initialize Micrometer metrics timer for observability
        this.riskCheckTimer = Timer.builder("titan.risk.engine.latency")
                .description("Latency of gRPC calls to AI Risk Engine")
                .register(meterRegistry);

        log.info("RiskEngineGrpcService initialized successfully with deadline: {}ms and local limit: {}", deadlineMs, localBlockLimit);
    }

    @CircuitBreaker(name = "risk-engine", fallbackMethod = "fallbackRiskCheck")
    public RiskCheckResponse analyzeTransaction(String userId, BigDecimal amount) {
        // 1. Strict Input Validation & Guard Clauses (Defensive Programming)
        validateInputs(userId, amount);

        log.debug("Calling AI Risk Engine | User: {} | Amount: ${}", userId, amount);

        // 2. Build Protobuf Request with explicit boundary isolation
        RiskCheckRequest request = RiskCheckRequest.newBuilder()
                .setUserId(userId)
                .setAmount(amount.doubleValue()) // Financial precision boundary mapping
                .build();

        // 3. Execute gRPC Call wrapped with Micrometer Timer
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            RiskCheckResponse response = riskStub
                    .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                    .checkRisk(request);

            sample.stop(riskCheckTimer);
            return response;
        } catch (Exception e) {
            sample.stop(riskCheckTimer);
            log.error("gRPC execution failed for user {}: {}", userId, e.getMessage());
            throw e; // Propagate exception to trigger Resilience4j Circuit Breaker fallback
        }
    }

    public RiskCheckResponse fallbackRiskCheck(String userId, BigDecimal amount, Throwable t) {
        log.warn("AI Service unreachable for user {} due to: {}. Applying fail-fast local limits safely.", userId, t.getMessage());

        // Safe null-fallback check for amount/limit
        BigDecimal safeAmount = amount != null ? amount : BigDecimal.ZERO;
        BigDecimal safeLimit = localBlockLimit != null ? localBlockLimit : new BigDecimal("100000");

        if (safeAmount.compareTo(safeLimit) >= 0) {
            return RiskCheckResponse.newBuilder()
                    .setAction("BLOCK")
                    .build();
        }

        return RiskCheckResponse.newBuilder()
                .setAction("MANUAL_REVIEW")
                .build();
    }

    private void validateInputs(String userId, BigDecimal amount) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("User ID must not be null or blank");
        }
        if (amount == null) {
            throw new IllegalArgumentException("Transaction amount must not be null");
        }
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Transaction amount cannot be negative");
        }
    }
}