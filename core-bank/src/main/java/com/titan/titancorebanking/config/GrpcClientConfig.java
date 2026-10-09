package com.titan.titancorebanking.config;

import com.titan.riskengine.RiskEngineServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@Slf4j
public class GrpcClientConfig {

    private final String aiHost;
    private final int aiPort;

    // [MODIFIED] Enforced constructor injection for immutable configuration state.
    // ប្រើប្រាស់ Constructor Injection ដើម្បីធានាថា Host និង Port មិនអាចកែប្រែបាន (Immutable) ក្រោយពេល Server ដើររួច។
    public GrpcClientConfig(
            @Value("${titan.ai.host:localhost}") final String aiHost,
            @Value("${titan.ai.port:50051}") final int aiPort) {
        this.aiHost = aiHost;
        this.aiPort = aiPort;
    }

    // [MODIFIED] Explicitly bound the shutdown method to prevent connection/port leaks on application teardown.
    // កំណត់ destroyMethod = "shutdown" ដើម្បីប្រាប់ Spring ឱ្យបិទ gRPC Connection ត្រឹមត្រូវពេលបិទ Server ការពារកុំឱ្យគាំង Port (Memory/Port Leaks)។
    @Bean(destroyMethod = "shutdown")
    public ManagedChannel managedChannel() {
        log.info("Initializing gRPC ManagedChannel for AI Risk Engine at {}:{}", aiHost, aiPort);

        /*
         * STAFF ENGINEER NOTE:
         * In a production environment (like your Proxmox homelab or Kubernetes), load balancers
         * and firewalls (like pfSense or iptables) silently drop idle TCP connections after ~5 minutes.
         * By configuring proactive Keep-Alive pings, we prevent "UNAVAILABLE: io exception" crashes
         * during periods of low transaction volume.
         */
        return ManagedChannelBuilder.forAddress(aiHost, aiPort)
                // [MODIFIED] Added network resilience configurations (Keep-Alive & Idle Timeouts).
                // បន្ថែមមុខងារ Keep-Alive ដើម្បីបញ្ជូនសញ្ញា (Ping) រៀងរាល់ ៣០វិនាទី ការពារមិនឱ្យ Firewall លួចកាត់ផ្តាច់ Connection ពេលប្រព័ន្ធស្ងាត់ (Idle)។
                .usePlaintext() // NOTE: Replace with .useTransportSecurity() if crossing public networks outside the service mesh
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .idleTimeout(5, TimeUnit.MINUTES)
                .build();
    }

    @Bean
    public RiskEngineServiceGrpc.RiskEngineServiceBlockingStub riskStub(final ManagedChannel channel) {
        return RiskEngineServiceGrpc.newBlockingStub(channel);
    }
}