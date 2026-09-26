package com.titan.titancorebanking.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Slf4j
public class TitanAiService {

    private final RestTemplate restTemplate;

    // អានពី application.yml ឬប្រើ default (localhost)
    @Value("${ai.host:localhost}")
    private String aiHost;

    @Value("${ai.port:50051}")
    private String aiPort;

    public void analyzeTransaction(String username, BigDecimal amount) {
        // បង្កើត URL ទៅកាន់ Python API
        String url = "http://" + aiHost + ":" + aiPort + "/analyze";

        log.info("📡 Calling AI Analysis at: {}", url);

        try {
            // 1. បង្កើត Payload
            AiRequest request = new AiRequest(username, amount);

            // 2. ហៅទៅ Python (POST Request)
            AiResponse response = restTemplate.postForObject(url, request, AiResponse.class);

            // 3. ពិនិត្យចម្លើយ
            if (response != null && "BLOCK".equalsIgnoreCase(response.getVerdict())) {
                log.warn("🚨 AI BLOCKED TRANSACTION for User: {}", username);
                throw new SecurityException("🛡️ TITAN AI SECURITY ALERT: Transaction Blocked! Risk too high.");
            }

            log.info("✅ AI Approved Transaction");

        } catch (SecurityException se) {
            throw se; // បញ្ជូន Error នេះទៅ Controller ឱ្យ Block
        } catch (Exception e) {
            // បើ AI ដាច់ (Connection Refused), យើងអនុញ្ញាតឱ្យទៅមុខសិន (Fail Open)
            // ដើម្បីកុំឱ្យស្ទះ Transaction ពេល Test
            log.error("⚠️ Could not contact AI Service: {}", e.getMessage());
        }
    }

    // DTOs សម្រាប់និយាយជាមួយ Python
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    static class AiRequest {
        private String username;
        private BigDecimal amount;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    static class AiResponse {
        private String verdict; // BLOCK or PASS
        private double riskScore;
    }
}