package com.titan.titancorebanking.security.redteam;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Task 8: Continuous Automated Red Teaming (CART)
 * Modernized to use RestClient for Virtual Thread compatibility.
 */
@Service
@Slf4j
public class CartRedTeamService {

    private final RestClient restClient;

    @Value("${cart.target.base-url:http://localhost:8080}")
    private String targetBaseUrl;

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    private final List<SecurityFinding> findings = new ArrayList<>();

    public CartRedTeamService(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Scheduled(cron = "0 0 1 * * *")
    public void runNightlyRedTeam() {
        if (!activeProfile.contains("staging")) {
            log.warn("CART: Skipping   only runs in staging profile. Current: {}", activeProfile);
            return;
        }

        findings.clear();
        log.info("  CART: Starting nightly red team run against {}", targetBaseUrl);

        probeSqlInjection();
        probeJwtForgery();
        probePathTraversal();
        probeIdorAccess();
        probeGrpcReflection();

        log.info("  CART: Run complete. {} findings.", findings.size());
    }

    @Scheduled(cron = "0 0 6 * * *")
    public void generateDailyReport() {
        if (!activeProfile.contains("staging")) return;

        StringBuilder report = new StringBuilder();
        report.append("=== TITAN CART SECURITY REPORT ===\n");
        report.append("Date: ").append(LocalDate.now()).append("\n");
        report.append("Target: ").append(targetBaseUrl).append("\n");
        report.append("Total Findings: ").append(findings.size()).append("\n\n");

        long critical = findings.stream().filter(f -> f.severity() == Severity.CRITICAL).count();
        long high = findings.stream().filter(f -> f.severity() == Severity.HIGH).count();
        long medium = findings.stream().filter(f -> f.severity() == Severity.MEDIUM).count();

        report.append(String.format("CRITICAL: %d | HIGH: %d | MEDIUM: %d\n\n", critical, high, medium));

        findings.forEach(f -> report.append(String.format("[%s] %s\n  Endpoint: %s\n  Payload: %s\n  Response: %s\n\n",
                f.severity(), f.title(), f.endpoint(), f.payload(), f.responseCode())));

        log.warn("\n{}", report);
    }

    private void probeSqlInjection() {
        List<String> payloads = List.of(
                "' OR '1'='1",
                "'; DROP TABLE transactions; --",
                "1 UNION SELECT username, password FROM users--",
                "' OR 1=1--",
                "admin'--"
        );
        List<String> endpoints = List.of(
                "/api/v1/users?username=",
                "/api/v1/accounts?accountNumber=",
                "/api/v1/transactions?id="
        );

        for (String endpoint : endpoints) {
            for (String payload : payloads) {
                try {
                    var response = restClient.get()
                            .uri(targetBaseUrl + endpoint + payload)
                            .header("Authorization", "Bearer invalid-token")
                            .retrieve()
                            .toBodilessEntity();

                    if (response.getStatusCode().value() == 200) {
                        findings.add(new SecurityFinding(
                                Severity.CRITICAL, "Potential SQL Injection",
                                endpoint, payload, "200", LocalDateTime.now()
                        ));
                    }
                } catch (RestClientException ignored) {
                    // 4xx/5xx = endpoint is rejecting the payload, which is expected
                }
            }
        }
    }

    private void probeJwtForgery() {
        String forgedHeader = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes());
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"admin\",\"roles\":[\"ADMIN\"],\"exp\":9999999999}".getBytes());
        String forgedToken = forgedHeader + "." + forgedPayload + ".";

        try {
            var response = restClient.get()
                    .uri(targetBaseUrl + "/api/v1/admin/users")
                    .header("Authorization", "Bearer " + forgedToken)
                    .retrieve()
                    .toBodilessEntity();

            if (response.getStatusCode().value() == 200) {
                findings.add(new SecurityFinding(
                        Severity.CRITICAL, "JWT alg:none Attack Succeeded",
                        "/api/v1/admin/users", "alg:none forged token", "200", LocalDateTime.now()
                ));
            }
        } catch (RestClientException ignored) {}
    }

    private void probePathTraversal() {
        List<String> payloads = List.of(
                "../../../../etc/passwd",
                "..%2F..%2F..%2Fetc%2Fpasswd",
                "%2e%2e%2f%2e%2e%2fetc%2fpasswd"
        );

        for (String payload : payloads) {
            try {
                String responseBody = restClient.get()
                        .uri(targetBaseUrl + "/api/v1/files/" + payload)
                        .retrieve()
                        .body(String.class);

                if (responseBody != null && responseBody.contains("root:")) {
                    findings.add(new SecurityFinding(
                            Severity.CRITICAL, "Path Traversal   /etc/passwd exposed",
                            "/api/v1/files/", payload, "200", LocalDateTime.now()
                    ));
                }
            } catch (RestClientException ignored) {}
        }
    }

    private void probeIdorAccess() {
        String lowPrivToken = generateLowPrivToken();
        for (int id = 1; id <= 10; id++) {
            try {
                var response = restClient.get()
                        .uri(targetBaseUrl + "/api/v1/accounts/" + id)
                        .header("Authorization", "Bearer " + lowPrivToken)
                        .retrieve()
                        .toBodilessEntity();

                if (response.getStatusCode().value() == 200) {
                    findings.add(new SecurityFinding(
                            Severity.HIGH, "Potential IDOR on account endpoint",
                            "/api/v1/accounts/" + id, "low-priv token", "200", LocalDateTime.now()
                    ));
                }
            } catch (RestClientException ignored) {}
        }
    }

    private void probeGrpcReflection() {
        try {
            var response = restClient.get()
                    .uri(targetBaseUrl.replace("8080", "50051") + "/grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo")
                    .retrieve()
                    .toBodilessEntity();

            int status = response.getStatusCode().value();
            if (status != 404 && status != 0) {
                findings.add(new SecurityFinding(
                        Severity.MEDIUM, "gRPC Reflection enabled in non-dev environment",
                        "grpc:50051", "reflection probe", String.valueOf(status), LocalDateTime.now()
                ));
            }
        } catch (RestClientException ignored) {}
    }

    private String generateLowPrivToken() {
        String header = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes());
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"cart-test-user\",\"roles\":[\"USER\"]}".getBytes());
        return header + "." + payload + ".cart-test-signature";
    }

    public record SecurityFinding(
            Severity severity, String title, String endpoint,
            String payload, String responseCode, LocalDateTime detectedAt
    ) {}

    public enum Severity { CRITICAL, HIGH, MEDIUM, LOW, INFO }

    public List<SecurityFinding> getFindings() { return List.copyOf(findings); }
}