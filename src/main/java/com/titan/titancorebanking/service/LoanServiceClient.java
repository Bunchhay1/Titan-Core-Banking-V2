package com.titan.titancorebanking.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * HTTP client in titan-core-banking that delegates all loan operations
 * to the titan-loans-service microservice.
 *
 * Usage (from a controller or another service):
 *   Map<?,?> loan = loanServiceClient.applyForLoan(payload, bearerToken);
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoanServiceClient {

    private final RestTemplate restTemplate;

    @Value("${titan.loans-service.url:http://titan-loans-service:8085}")
    private String loansServiceUrl;

    // ── POST /api/v1/loans/apply ─────────────────────────────────────────────

    public Map<String, Object> applyForLoan(Map<String, Object> payload, String bearerToken) {
        String url = loansServiceUrl + "/api/v1/loans/apply";
        log.info("🔗 [LoanServiceClient] POST {} — delegating loan application", url);
        return post(url, payload, bearerToken);
    }

    // ── PUT /api/v1/loans/{id}/approve ───────────────────────────────────────

    public Map<String, Object> approveLoan(Long loanId, String bearerToken) {
        String url = loansServiceUrl + "/api/v1/loans/" + loanId + "/approve";
        log.info("🔗 [LoanServiceClient] PUT {} — approving loan id={}", url, loanId);
        return put(url, null, bearerToken);
    }

    // ── PUT /api/v1/loans/{id}/reject ────────────────────────────────────────

    public Map<String, Object> rejectLoan(Long loanId, String bearerToken) {
        String url = loansServiceUrl + "/api/v1/loans/" + loanId + "/reject";
        log.info("🔗 [LoanServiceClient] PUT {} — rejecting loan id={}", url, loanId);
        return put(url, null, bearerToken);
    }

    // ── GET /api/v1/loans/{id} ───────────────────────────────────────────────

    public Map<String, Object> getLoanById(Long loanId, String bearerToken) {
        String url = loansServiceUrl + "/api/v1/loans/" + loanId;
        return get(url, bearerToken);
    }

    // ── GET /api/v1/loans/my ─────────────────────────────────────────────────

    public List<Map<String, Object>> getMyLoans(String bearerToken) {
        String url = loansServiceUrl + "/api/v1/loans/my";
        return getList(url, bearerToken);
    }

    // ── GET /api/v1/loans/account/{accountId} ────────────────────────────────

    public List<Map<String, Object>> getLoansByAccount(Long accountId, String bearerToken) {
        String url = loansServiceUrl + "/api/v1/loans/account/" + accountId;
        return getList(url, bearerToken);
    }

    // ── GET /api/v1/loans/{id}/repayments ────────────────────────────────────

    public List<Map<String, Object>> getRepaymentSchedule(Long loanId, String bearerToken) {
        String url = loansServiceUrl + "/api/v1/loans/" + loanId + "/repayments";
        return getList(url, bearerToken);
    }

    // ─── Private HTTP helpers ─────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String url, Object body, String bearerToken) {
        try {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(body, headers(bearerToken)),
                    new ParameterizedTypeReference<>() {});
            return resp.getBody();
        } catch (HttpClientErrorException e) {
            log.warn("⚠️ [LoanServiceClient] HTTP {} from loans-service: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        } catch (ResourceAccessException e) {
            log.error("❌ [LoanServiceClient] Cannot reach titan-loans-service: {}", e.getMessage());
            throw new IllegalStateException("Loan service is currently unavailable. Please try again later.", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> put(String url, Object body, String bearerToken) {
        try {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    url, HttpMethod.PUT,
                    new HttpEntity<>(body, headers(bearerToken)),
                    new ParameterizedTypeReference<>() {});
            return resp.getBody();
        } catch (HttpClientErrorException e) {
            log.warn("⚠️ [LoanServiceClient] HTTP {} from loans-service: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        } catch (ResourceAccessException e) {
            log.error("❌ [LoanServiceClient] Cannot reach titan-loans-service: {}", e.getMessage());
            throw new IllegalStateException("Loan service is currently unavailable. Please try again later.", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String url, String bearerToken) {
        try {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    url, HttpMethod.GET,
                    new HttpEntity<>(headers(bearerToken)),
                    new ParameterizedTypeReference<>() {});
            return resp.getBody();
        } catch (HttpClientErrorException e) {
            log.warn("⚠️ [LoanServiceClient] HTTP {} from loans-service: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        } catch (ResourceAccessException e) {
            log.error("❌ [LoanServiceClient] Cannot reach titan-loans-service: {}", e.getMessage());
            throw new IllegalStateException("Loan service is currently unavailable. Please try again later.", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getList(String url, String bearerToken) {
        try {
            ResponseEntity<List<Map<String, Object>>> resp = restTemplate.exchange(
                    url, HttpMethod.GET,
                    new HttpEntity<>(headers(bearerToken)),
                    new ParameterizedTypeReference<>() {});
            return resp.getBody();
        } catch (HttpClientErrorException e) {
            log.warn("⚠️ [LoanServiceClient] HTTP {} from loans-service: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        } catch (ResourceAccessException e) {
            log.error("❌ [LoanServiceClient] Cannot reach titan-loans-service: {}", e.getMessage());
            throw new IllegalStateException("Loan service is currently unavailable. Please try again later.", e);
        }
    }

    private HttpHeaders headers(String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.setBearerAuth(
                    bearerToken.startsWith("Bearer ") ? bearerToken.substring(7) : bearerToken);
        }
        return headers;
    }
}
