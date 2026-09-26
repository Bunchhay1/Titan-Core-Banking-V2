package com.titan.titancorebanking.service;

import com.titan.titancorebanking.dto.request.RiskCheckRequest;
import com.titan.titancorebanking.dto.response.RiskCheckResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

@Service
public class RiskEngineService {

    private static final Logger logger = LoggerFactory.getLogger(RiskEngineService.class);
    private final RestClient restClient;
    private final String riskEngineUrl;

    // Constructor Injection: ទាញយក URL ពី application.properties
    public RiskEngineService(RestClient.Builder builder,
                             @Value("${risk.engine.url:http://localhost:8082}") String riskEngineUrl) {
        this.restClient = builder.build();
        this.riskEngineUrl = riskEngineUrl;
    }

    /**
     * មុខងារ៖ សួរទៅកាន់ Python Risk Engine ថាតើការផ្ទេរនេះមានហានិភ័យទេ?
     */
    public RiskCheckResponse analyzeTransaction(String username, BigDecimal amount) {
        RiskCheckRequest request = new RiskCheckRequest(username, amount);

        logger.info("🤖 AI Risk Check: Asking Python Engine for user: {}", username);

        try {
            // 📞 Calling Python API (POST http://localhost:8082/check-risk)
            RiskCheckResponse response = restClient.post()
                    .uri(riskEngineUrl + "/check-risk")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(RiskCheckResponse.class);

            logger.info("🤖 AI Verdict: {}", response);
            return response;

        } catch (Exception e) {
            // 🛡️ Fail-Open Strategy:
            // បើ Python ដាច់ភ្លើង ឬគាំង (Offline) យើងចាត់ទុកថា "ALLOW" (កុំឱ្យអតិថិជនជាប់គាំង)
            logger.error("⚠️ Risk Engine is OFFLINE or Error: {}. Defaulting to ALLOW.", e.getMessage());

            // Return default "ALLOW" so transaction can proceed
            return new RiskCheckResponse("UNKNOWN", "BLOCK"); // 🔒 Fail-Safe (បើដាច់ភ្លើង គឺបិទទាំងអស់)
        }
    }
}