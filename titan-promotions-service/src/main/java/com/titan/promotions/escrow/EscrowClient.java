package com.titan.promotions.escrow;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@Slf4j
public class EscrowClient {
    private final EscrowServiceGrpc.EscrowServiceBlockingStub stub;
    
    public EscrowClient(@Value("${titan.core-banking.grpc.host:localhost}") String host,
                        @Value("${titan.core-banking.grpc.port:9090}") int port) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        this.stub = EscrowServiceGrpc.newBlockingStub(channel);
    }
    
    public String lockCampaignBudget(Long campaignId, BigDecimal amount, String currency) {
        EscrowProto.LockFundsRequest request = EscrowProto.LockFundsRequest.newBuilder()
            .setCampaignId(campaignId)
            .setAmount(amount.toString())
            .setCurrency(currency)
            .build();
        
        EscrowProto.LockFundsResponse response = stub.lockFunds(request);
        if (response.getSuccess()) {
            log.info("Locked {} {} for campaign {} - escrowId: {}", amount, currency, campaignId, response.getEscrowId());
            return response.getEscrowId();
        } else {
            log.error("Failed to lock funds: {}", response.getMessage());
            throw new RuntimeException("Escrow lock failed: " + response.getMessage());
        }
    }
    
    public void releaseFunds(String escrowId, Long accountId, BigDecimal amount) {
        EscrowProto.ReleaseFundsRequest request = EscrowProto.ReleaseFundsRequest.newBuilder()
            .setEscrowId(escrowId)
            .setAccountId(accountId)
            .setAmount(amount.toString())
            .build();
        
        EscrowProto.ReleaseFundsResponse response = stub.releaseFunds(request);
        if (!response.getSuccess()) {
            throw new RuntimeException("Escrow release failed: " + response.getMessage());
        }
        log.info("Released {} from escrow {} to account {}", amount, escrowId, accountId);
    }
}
