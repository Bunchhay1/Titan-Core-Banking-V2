package com.titan.promotions.wasm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class WasmExportService {
    private final WebClient gatewayClient = WebClient.builder()
        .baseUrl("http://localhost:8080")
        .build();
    
    public void exportRulesToGateway(List<WasmRule> rules) {
        Map<String, Object> payload = Map.of("rules", rules);
        
        gatewayClient.post()
            .uri("/internal/wasm/update")
            .bodyValue(payload)
            .retrieve()
            .bodyToMono(String.class)
            .doOnSuccess(response -> log.info("Exported {} WASM rules to gateway", rules.size()))
            .doOnError(e -> log.error("Failed to export WASM rules", e))
            .subscribe();
    }
    
    public byte[] compileRuleToWasm(String spelExpression) {
        // Stub: In production, use a SpEL-to-WASM compiler or pre-compiled templates
        log.info("Compiling rule to WASM: {}", spelExpression);
        return new byte[]{0x00, 0x61, 0x73, 0x6d}; // WASM magic number
    }
}
