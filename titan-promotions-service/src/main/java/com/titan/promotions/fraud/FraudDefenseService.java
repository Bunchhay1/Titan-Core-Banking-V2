package com.titan.promotions.fraud;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@Slf4j
public class FraudDefenseService {
    
    private final ManagedChannel channel;
    private static final BigDecimal HIGH_VALUE_THRESHOLD = new BigDecimal("50.00");
    
    public FraudDefenseService(@Value("${ai.service.host:localhost}") String host,
                               @Value("${ai.service.port:50051}") int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .build();
    }
    
    public boolean shouldGrantReward(Long accountId, String deviceFingerprint, String ipAddress, BigDecimal rewardAmount) {
        if (rewardAmount.compareTo(HIGH_VALUE_THRESHOLD) <= 0) {
            return true; // Low-value rewards bypass fraud check
        }
        
        try {
            // gRPC call to AI Risk Engine
            var riskScore = queryRiskEngine(accountId, deviceFingerprint, ipAddress);
            
            if (riskScore > 0.7) {
                log.warn("Sybil attack detected: account={}, device={}, ip={}, risk={}", 
                    accountId, deviceFingerprint, ipAddress, riskScore);
                return false; // Silently drop reward
            }
            
            return true;
        } catch (Exception e) {
            log.error("Risk engine unavailable, allowing reward", e);
            return true; // Fail open
        }
    }
    
    private double queryRiskEngine(Long accountId, String deviceFingerprint, String ipAddress) {
        // Stub: Replace with actual gRPC call to TitanAiService
        return Math.random();
    }
}
