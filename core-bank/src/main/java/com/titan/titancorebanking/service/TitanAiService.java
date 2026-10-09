package com.titan.titancorebanking.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

@Service
@Slf4j
public class TitanAiService {

    private final RestClient restClient;
    private final String aiAnalyzeUri;

    // [MODIFIED] Replaced legacy RestTemplate with Spring Boot 3.2's modern, fluent RestClient.
    // ប្រើប្រាស់ RestClient ជំនួសឲ្យ RestTemplate ចាស់ ដើម្បីដំណើរការ HTTP Request បានលឿន និងមានសុវត្ថិភាពជាងមុន។
    // [MODIFIED] Constructor injection for strict immutability.
    public TitanAiService(RestClient.Builder restClientBuilder,
                          @Value("${ai.host:localhost}") String aiHost,
                          @Value("${ai.port:50051}") String aiPort) {
        this.restClient = restClientBuilder.build();
        this.aiAnalyzeUri = String.format("http://%s:%s/analyze", aiHost, aiPort);
    }

    // [MODIFIED] Added Resilience4j Circuit Breaker for Enterprise Fault Tolerance.
    // ដាក់ប្រព័ន្ធការពារ (Circuit Breaker)។ ប្រសិនបើ AI Service គាំង វានឹងមិនធ្វើឲ្យប្រព័ន្ធធនាគារមេគាំងតាមនោះទេ វានឹងរត់ចូល Method failOpenFallback ដោយស្វ័យប្រវត្តិ។
    @CircuitBreaker(name = "aiRiskService", fallbackMethod = "failOpenFallback")
    public void analyzeTransaction(final String username, final BigDecimal amount) {
        log.debug("Initiating AI Risk Analysis for user: {} | Amount: {}", username, amount);

        var requestPayload = new AiRequest(username, amount);

        var response = restClient.post()
                .uri(aiAnalyzeUri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestPayload)
                .retrieve()
                .body(AiResponse.class);

        if (response != null && "BLOCK".equalsIgnoreCase(response.verdict())) {
            log.warn("SECURITY ALERT: Transaction BLOCKED by AI Risk Engine. User: {} | Score: {}",
                    username, response.riskScore());
            throw new SecurityException("TITAN AI SECURITY ALERT: Transaction Blocked! Risk too high.");
        }

        log.debug("AI Risk Analysis Passed for user: {}", username);
    }

    // [MODIFIED] Explicit Fallback Method handling (Fail-Open Strategy).
    // ប្រសិនបើ AI រអាក់រអួល ប្រព័ន្ធនឹងអនុញ្ញាតឲ្យប្រតិបត្តិការឆ្លងកាត់ (Fail-Open) ដើម្បីកុំឲ្យរាំងស្ទះអតិថិជន ប៉ុន្តែវានឹងកត់ត្រា Error យ៉ាងច្បាស់។
    public void failOpenFallback(final String username, final BigDecimal amount, final Throwable t) {
        if (t instanceof SecurityException) {
            throw (SecurityException) t; // Do not swallow actual security block exceptions
        }
        log.error("AI Risk Service unavailable (Circuit Breaker OPEN). Failing OPEN for user: {}. Reason: {}",
                username, t.getMessage());
    }

    // [MODIFIED] Replaced Lombok @Data classes with pure Java Records for strict immutability and zero-boilerplate.
    // ប្តូរពី Class ធម្មតា មកប្រើ Java 16+ Records សម្រាប់ DTOs ដើម្បីកាត់បន្ថយទំហំ Memory និងធានាថា Data មិនអាចកែប្រែបាន (Immutable)។
    record AiRequest(String username, BigDecimal amount) {}
    record AiResponse(String verdict, double riskScore) {}
}