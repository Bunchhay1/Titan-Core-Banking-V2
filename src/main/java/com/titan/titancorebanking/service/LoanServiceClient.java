package com.titan.titancorebanking.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.Map;

/**
 * Modernized to use Spring Boot 3.2 RestClient.
 * RestClient provides a fluent, functional API and works seamlessly
 * with Java 21 Virtual Threads, unlike the legacy RestTemplate.
 */
@Service
@Slf4j
public class LoanServiceClient {

    private final RestClient restClient;

    @Value("${titan.loans-service.url:http://titan-loans-service:8085}")
    private String loansServiceUrl;

    public LoanServiceClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    public Map<String, Object> applyForLoan(Map<String, Object> payload, String bearerToken) {
        String url = loansServiceUrl + "/api/v1/loans/apply";
        log.info("  [LoanServiceClient] POST {}   delegating loan application", url);
        return post(url, payload, bearerToken);
    }

    public Map<String, Object> approveLoan(Long loanId, String bearerToken) {
        String url = loansServiceUrl + "/api/v1/loans/" + loanId + "/approve";
        log.info("  [LoanServiceClient] PUT {}   approving loan id={}", url, loanId);
        return put(url, null, bearerToken);
    }

    public Map<String, Object> rejectLoan(Long loanId, String bearerToken) {
        String url = loansServiceUrl + "/api/v1/loans/" + loanId + "/reject";
        log.info("  [LoanServiceClient] PUT {}   rejecting loan id={}", url, loanId);
        return put(url, null, bearerToken);
    }

    public Map<String, Object> getLoanById(Long loanId, String bearerToken) {
        String url = loansServiceUrl + "/api/v1/loans/" + loanId;
        return get(url, bearerToken);
    }

    public List<Map<String, Object>> getMyLoans(String bearerToken) {
        String url = loansServiceUrl + "/api/v1/loans/my";
        return getList(url, bearerToken);
    }

    public List<Map<String, Object>> getLoansByAccount(Long accountId, String bearerToken) {
        String url = loansServiceUrl + "/api/v1/loans/account/" + accountId;
        return getList(url, bearerToken);
    }

    public List<Map<String, Object>> getRepaymentSchedule(Long loanId, String bearerToken) {
        String url = loansServiceUrl + "/api/v1/loans/" + loanId + "/repayments";
        return getList(url, bearerToken);
    }

    private Map<String, Object> post(String url, Object body, String bearerToken) {
        try {
            return restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        if (bearerToken != null && !bearerToken.isBlank()) {
                            headers.setBearerAuth(bearerToken.startsWith("Bearer ") ? bearerToken.substring(7) : bearerToken);
                        }
                    })
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (HttpClientErrorException e) {
            log.warn("  [LoanServiceClient] HTTP {} from loans-service: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        } catch (ResourceAccessException e) {
            log.error("  [LoanServiceClient] Cannot reach titan-loans-service: {}", e.getMessage());
            throw new IllegalStateException("Loan service is currently unavailable. Please try again later.", e);
        }
    }

    private Map<String, Object> put(String url, Object body, String bearerToken) {
        try {
            var request = restClient.put()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        if (bearerToken != null && !bearerToken.isBlank()) {
                            headers.setBearerAuth(bearerToken.startsWith("Bearer ") ? bearerToken.substring(7) : bearerToken);
                        }
                    });

            if (body != null) {
                request.body(body);
            }

            return request.retrieve().body(new ParameterizedTypeReference<>() {});
        } catch (HttpClientErrorException e) {
            log.warn("  [LoanServiceClient] HTTP {} from loans-service: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        } catch (ResourceAccessException e) {
            log.error("  [LoanServiceClient] Cannot reach titan-loans-service: {}", e.getMessage());
            throw new IllegalStateException("Loan service is currently unavailable. Please try again later.", e);
        }
    }

    private Map<String, Object> get(String url, String bearerToken) {
        try {
            return restClient.get()
                    .uri(url)
                    .headers(headers -> {
                        if (bearerToken != null && !bearerToken.isBlank()) {
                            headers.setBearerAuth(bearerToken.startsWith("Bearer ") ? bearerToken.substring(7) : bearerToken);
                        }
                    })
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (HttpClientErrorException e) {
            log.warn("  [LoanServiceClient] HTTP {} from loans-service: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        } catch (ResourceAccessException e) {
            log.error("  [LoanServiceClient] Cannot reach titan-loans-service: {}", e.getMessage());
            throw new IllegalStateException("Loan service is currently unavailable. Please try again later.", e);
        }
    }

    private List<Map<String, Object>> getList(String url, String bearerToken) {
        try {
            return restClient.get()
                    .uri(url)
                    .headers(headers -> {
                        if (bearerToken != null && !bearerToken.isBlank()) {
                            headers.setBearerAuth(bearerToken.startsWith("Bearer ") ? bearerToken.substring(7) : bearerToken);
                        }
                    })
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (HttpClientErrorException e) {
            log.warn("  [LoanServiceClient] HTTP {} from loans-service: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        } catch (ResourceAccessException e) {
            log.error("  [LoanServiceClient] Cannot reach titan-loans-service: {}", e.getMessage());
            throw new IllegalStateException("Loan service is currently unavailable. Please try again later.", e);
        }
    }
}