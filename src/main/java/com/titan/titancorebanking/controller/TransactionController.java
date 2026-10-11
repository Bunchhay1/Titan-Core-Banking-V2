package com.titan.titancorebanking.controller;

import java.util.Map;
import com.titan.titancorebanking.dto.request.TransactionRequest;
import com.titan.titancorebanking.dto.response.TransactionResponse;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.service.TransactionService;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;


import java.util.List;
import java.util.regex.Pattern;

/**
 * Hardened Transaction Controller.
 * Adds Rate Limiting to financial mutation endpoints to prevent programmatic drain attacks.
 */
@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Transaction API", description = "Core banking operations (Transfer, Withdraw, Deposit)")
public class TransactionController {

    private final TransactionService transactionService;

    private static final Pattern SWIFT_PATTERN = Pattern.compile("^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$");
    private static final Pattern IBAN_PATTERN = Pattern.compile("^[A-Z]{2}\\d{2}[A-Z0-9]{4,30}$");

    @PostMapping("/transfer")
    @RateLimiter(name = "financial-operations")
    @Operation(summary = "Execute Fund Transfer", description = "Transfers money between accounts safely.")
    public ResponseEntity<TransactionResponse> transfer(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody TransactionRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {

        // Inject idempotency key from header into request context if present
        TransactionRequest enrichedRequest = idempotencyKey != null
                ? request.withIdempotencyKey(idempotencyKey)
                : request;

        log.info("Transfer Request: {} -> {}", enrichedRequest.fromAccountNumber(), enrichedRequest.toAccountNumber());
        Transaction tx = transactionService.transfer(enrichedRequest, userDetails.getUsername());
        return ResponseEntity.ok(toTransactionResponse(tx));
    }

    @PostMapping("/withdraw")
    @RateLimiter(name = "financial-operations")
    @Operation(summary = "Withdraw Money", description = "Deducts balance from account.")
    public ResponseEntity<TransactionResponse> withdraw(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody TransactionRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {

        TransactionRequest enrichedRequest = idempotencyKey != null ? request.withIdempotencyKey(idempotencyKey) : request;
        Transaction tx = transactionService.withdraw(enrichedRequest, userDetails.getUsername());
        return ResponseEntity.ok(toTransactionResponse(tx));
    }

    @PostMapping("/deposit")
    @RateLimiter(name = "financial-operations")
    @Operation(summary = "Deposit Money", description = "Adds balance to account.")
    public ResponseEntity<TransactionResponse> deposit(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody TransactionRequest request) {

        TransactionRequest enrichedRequest = idempotencyKey != null ? request.withIdempotencyKey(idempotencyKey) : request;
        Transaction tx = transactionService.deposit(enrichedRequest);
        return ResponseEntity.ok(toTransactionResponse(tx));
    }

    @PostMapping("/international")
    @RateLimiter(name = "financial-operations")
    @Operation(summary = "International Transfer", description = "SWIFT/IBAN validated transfer.")
    public ResponseEntity<Object> internationalTransfer(
            @RequestBody TransactionRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {

        if (request.swiftCode() == null || !SWIFT_PATTERN.matcher(request.swiftCode()).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid SWIFT Code"));
        }

        if (request.iban() == null || !IBAN_PATTERN.matcher(request.iban()).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid IBAN Format"));
        }

        // Handled by downstream correspondent banking service in a full implementation
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of("message", "International transfers routing disabled in current region"));
    }

    @GetMapping
    @Bulkhead(name = "non-critical", fallbackMethod = "getHistoryFallback")
    @Operation(summary = "Get Transaction History", description = "Returns all transactions for logged-in user.")
    public ResponseEntity<List<TransactionResponse>> getHistory(@AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(transactionService.getTransactionHistory(userDetails.getUsername()));
    }

    public ResponseEntity<List<TransactionResponse>> getHistoryFallback(UserDetails userDetails, Exception e) {
        log.warn("History request rejected for {} - system at capacity", userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    private TransactionResponse toTransactionResponse(Transaction tx) {
        String currency = tx.getFromAccount() != null && tx.getFromAccount().getCurrency() != null
                ? tx.getFromAccount().getCurrency().name()
                : (tx.getToAccount() != null && tx.getToAccount().getCurrency() != null
                ? tx.getToAccount().getCurrency().name()
                : "USD");

        return new TransactionResponse(
                tx.getId(),
                tx.getTransactionType().name(),
                tx.getAmount(),
                tx.getFromAccount() != null ? tx.getFromAccount().getAccountNumber() : null,
                tx.getToAccount() != null ? tx.getToAccount().getAccountNumber() : null,
                tx.getStatus().name(),
                tx.getNote(),
                tx.getTimestamp(),
                currency,
                tx.getFee() != null ? tx.getFee() : java.math.BigDecimal.ZERO,
                tx.getTransactionReference()
        );
    }
}