package com.titan.titancorebanking.config;

import com.titan.riskengine.RiskEngineServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value; // ✅ Import ថ្មី
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcClientConfig {

    // ✅ អានតម្លៃពី application.properties
    @Value("${titan.ai.host}")
    private String aiHost;

    @Value("${titan.ai.port}")
    private int aiPort;

    @Bean
    public ManagedChannel managedChannel() {
        // ប្រើតម្លៃដែលអានបាន (លែង Hardcode ហើយ)
        return ManagedChannelBuilder.forAddress(aiHost, aiPort)
                .usePlaintext()
                .build();
    }

    @Bean
    public RiskEngineServiceGrpc.RiskEngineServiceBlockingStub riskStub(ManagedChannel channel) {
        return RiskEngineServiceGrpc.newBlockingStub(channel);
    }
}