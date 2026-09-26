package com.titan.core.grpc;

import com.titan.promotions.escrow.EscrowProto;
import com.titan.promotions.escrow.EscrowServiceGrpc;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@GrpcService
@Slf4j
public class EscrowGrpcService extends EscrowServiceGrpc.EscrowServiceImplBase {
    private final Map<String, BigDecimal> escrowLedger = new ConcurrentHashMap<>();
    
    @Override
    public void lockFunds(EscrowProto.LockFundsRequest request, 
                         StreamObserver<EscrowProto.LockFundsResponse> responseObserver) {
        String escrowId = "ESC-" + UUID.randomUUID();
        BigDecimal amount = new BigDecimal(request.getAmount());
        
        escrowLedger.put(escrowId, amount);
        log.info("Locked {} {} for campaign {} - escrowId: {}", 
            amount, request.getCurrency(), request.getCampaignId(), escrowId);
        
        EscrowProto.LockFundsResponse response = EscrowProto.LockFundsResponse.newBuilder()
            .setSuccess(true)
            .setEscrowId(escrowId)
            .setMessage("Funds locked successfully")
            .build();
        
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
    
    @Override
    public void releaseFunds(EscrowProto.ReleaseFundsRequest request,
                            StreamObserver<EscrowProto.ReleaseFundsResponse> responseObserver) {
        BigDecimal locked = escrowLedger.get(request.getEscrowId());
        BigDecimal releaseAmount = new BigDecimal(request.getAmount());
        
        if (locked == null || locked.compareTo(releaseAmount) < 0) {
            responseObserver.onNext(EscrowProto.ReleaseFundsResponse.newBuilder()
                .setSuccess(false)
                .setMessage("Insufficient escrow balance")
                .build());
        } else {
            escrowLedger.put(request.getEscrowId(), locked.subtract(releaseAmount));
            log.info("Released {} from escrow {} to account {}", 
                releaseAmount, request.getEscrowId(), request.getAccountId());
            
            responseObserver.onNext(EscrowProto.ReleaseFundsResponse.newBuilder()
                .setSuccess(true)
                .setMessage("Funds released")
                .build());
        }
        responseObserver.onCompleted();
    }
}
