package com.titan.titancorebanking.controller;

import com.titan.titancorebanking.service.LoanServiceClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Proxy controller — forwards all /api/v1/loans/** requests to titan-loans-service.
 *
 * This maintains backward-compatibility: clients that still call core-banking
 * for loan endpoints continue to work. Internally the request is proxied to
 * titan-loans-service via the LoanServiceClient REST client.
 */
@RestController
@RequestMapping("/api/v1/loans")
@RequiredArgsConstructor
@Tag(name = "Loans (proxy)", description = "Loan operations — proxied to titan-loans-service")
public class LoanProxyController {

    private final LoanServiceClient loanServiceClient;

    // ── POST /api/v1/loans/apply ─────────────────────────────────────────────
    @PostMapping("/apply")
    @Operation(summary = "Apply for a loan (proxied to titan-loans-service)")
    public ResponseEntity<Map<String, Object>> apply(
            @RequestBody Map<String, Object> payload,
            HttpServletRequest request) {

        return ResponseEntity.ok(
                loanServiceClient.applyForLoan(payload, extractToken(request)));
    }

    // ── PUT /api/v1/loans/{id}/approve ───────────────────────────────────────
    @PutMapping("/{id}/approve")
    @Operation(summary = "Approve a loan (proxied to titan-loans-service)")
    public ResponseEntity<Map<String, Object>> approve(
            @PathVariable Long id,
            HttpServletRequest request) {

        return ResponseEntity.ok(
                loanServiceClient.approveLoan(id, extractToken(request)));
    }

    // ── PUT /api/v1/loans/{id}/reject ────────────────────────────────────────
    @PutMapping("/{id}/reject")
    @Operation(summary = "Reject a loan (proxied to titan-loans-service)")
    public ResponseEntity<Map<String, Object>> reject(
            @PathVariable Long id,
            HttpServletRequest request) {

        return ResponseEntity.ok(
                loanServiceClient.rejectLoan(id, extractToken(request)));
    }

    // ── GET /api/v1/loans/{id} ───────────────────────────────────────────────
    @GetMapping("/{id}")
    @Operation(summary = "Get loan by ID (proxied to titan-loans-service)")
    public ResponseEntity<Map<String, Object>> getById(
            @PathVariable Long id,
            HttpServletRequest request) {

        return ResponseEntity.ok(
                loanServiceClient.getLoanById(id, extractToken(request)));
    }

    // ── GET /api/v1/loans/my ─────────────────────────────────────────────────
    @GetMapping("/my")
    @Operation(summary = "Get my loans (proxied to titan-loans-service)")
    public ResponseEntity<List<Map<String, Object>>> myLoans(HttpServletRequest request) {
        return ResponseEntity.ok(
                loanServiceClient.getMyLoans(extractToken(request)));
    }

    // ── GET /api/v1/loans/account/{accountId} ────────────────────────────────
    @GetMapping("/account/{accountId}")
    @Operation(summary = "Get loans by account (proxied to titan-loans-service)")
    public ResponseEntity<List<Map<String, Object>>> getByAccount(
            @PathVariable Long accountId,
            HttpServletRequest request) {

        return ResponseEntity.ok(
                loanServiceClient.getLoansByAccount(accountId, extractToken(request)));
    }

    // ── GET /api/v1/loans/{id}/repayments ────────────────────────────────────
    @GetMapping("/{id}/repayments")
    @Operation(summary = "Get repayment schedule (proxied to titan-loans-service)")
    public ResponseEntity<List<Map<String, Object>>> repayments(
            @PathVariable Long id,
            HttpServletRequest request) {

        return ResponseEntity.ok(
                loanServiceClient.getRepaymentSchedule(id, extractToken(request)));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String extractToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        return (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7) : null;
    }
}
