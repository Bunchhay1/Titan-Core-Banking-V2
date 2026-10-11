package com.titan.titancorebanking.config;

import com.titan.riskengine.RiskEngineServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Hardened gRPC Client Configuration.
 * Configures aggressive keep-alive pings to prevent cloud load balancers or
 * firewalls from silently dropping idle TCP connections to the AI Risk Engine.
 */
@Configuration
public class GrpcClientConfig {

    @Value("${ai.host:localhost}")
    private String aiHost;

    @Value("${ai.port:50051}")
    private int aiPort;

    @Bean
    public ManagedChannel riskEngineChannel() {
        return ManagedChannelBuilder.forAddress(aiHost, aiPort)
                .usePlaintext() // Replace with useTransportSecurity() for production mTLS
                .keepAliveTime(30, TimeUnit.SECONDS) // Ping every 30s to keep TCP connection hot
                .keepAliveTimeout(5, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .idleTimeout(60, TimeUnit.MINUTES)
                .build();
    }

    @Bean
    public RiskEngineServiceGrpc.RiskEngineServiceBlockingStub riskEngineStub(ManagedChannel channel) {
        return RiskEngineServiceGrpc.newBlockingStub(channel);
    }
}