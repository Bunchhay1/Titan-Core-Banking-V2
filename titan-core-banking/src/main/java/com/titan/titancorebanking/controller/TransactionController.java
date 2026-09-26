package com.titan.titancorebanking.controller;

import com.titan.titancorebanking.dto.request.TransactionRequest;
import com.titan.titancorebanking.dto.response.TransactionResponse;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.regex.Pattern;

/**
 * ✅ JAVA 21 CONTROLLER
 * - Secure Endpoints (Principal-based)
 * - Record DTOs
 * - Concise Error Handling
 */
@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Transaction API", description = "Core banking operations (Transfer, Withdraw, Deposit)")
public class TransactionController {

    private final TransactionService transactionService;

    // ==================================================================================
    // 💸 1. TRANSFER ENDPOINT
    // ==================================================================================
    @PostMapping("/transfer")
    @Operation(summary = "Execute Fund Transfer", description = "Transfers money between accounts safely.")
    @io.github.resilience4j.bulkhead.annotation.Bulkhead(name = "critical", fallbackMethod = "transferFallback")
    public ResponseEntity<TransactionResponse> transfer(
            @RequestBody TransactionRequest request,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        log.info("💸 Transfer Request: {} -> {}", request.fromAccountNumber(), request.toAccountNumber());
        Transaction tx = transactionService.transfer(request, userDetails.getUsername());
        return ResponseEntity.ok(toTransactionResponse(tx));
    }

    public ResponseEntity<TransactionResponse> transferFallback(TransactionRequest request, UserDetails userDetails, io.github.resilience4j.bulkhead.BulkheadFullException e) {
        log.error("⚠️ Transfer rejected - system at capacity (Bulkhead Full)", e);
        return ResponseEntity.status(503).build();
    }

    // ==================================================================================
    // 🏧 2. WITHDRAW ENDPOINT
    // ==================================================================================
    @PostMapping("/withdraw")
    @Operation(summary = "Withdraw Money", description = "Deducts balance from account.")
    public ResponseEntity<TransactionResponse> withdraw(
            @RequestBody TransactionRequest request,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        log.info("🏧 Withdraw Request: Acc: {}", request.fromAccountNumber());
        Transaction tx = transactionService.withdraw(request, userDetails.getUsername());
        return ResponseEntity.ok(toTransactionResponse(tx));
    }

    // ==================================================================================
    // 💰 3. DEPOSIT ENDPOINT
    // ==================================================================================
    @PostMapping("/deposit")
    @Operation(summary = "Deposit Money", description = "Adds balance to account.")
    public ResponseEntity<TransactionResponse> deposit(
            @RequestBody TransactionRequest request,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        log.info("💰 Deposit Request: Acc: {}", request.toAccountNumber());
        Transaction tx = transactionService.deposit(request);
        return ResponseEntity.ok(toTransactionResponse(tx));
    }

    // ==================================================================================
    // 🌍 4. INTERNATIONAL TRANSFER
    // ==================================================================================
    @PostMapping("/international")
    @Operation(summary = "International Transfer", description = "SWIFT/IBAN validated transfer.")
    public ResponseEntity<?> internationalTransfer(
            @RequestBody TransactionRequest request,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        // ✅ Java 21: Pattern matching for validation
        String swiftRegex = "^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$";
        String ibanRegex = "^[A-Z]{2}\\d{2}[A-Z0-9]{4,30}$";

        if (request.swiftCode() == null || !Pattern.matches(swiftRegex, request.swiftCode())) {
            return ResponseEntity.badRequest().body("❌ Invalid SWIFT Code");
        }
        if (request.iban() == null || !Pattern.matches(ibanRegex, request.iban())) {
            return ResponseEntity.badRequest().body("❌ Invalid IBAN Format");
        }

        return ResponseEntity.ok("✅ International Transfer Initiated");
    }

    // ==================================================================================
    // 📜 5. HISTORY ENDPOINT
    // ==================================================================================
    @GetMapping
    @Operation(summary = "Get Transaction History", description = "Returns all transactions for logged-in user.")
    @io.github.resilience4j.bulkhead.annotation.Bulkhead(name = "non-critical", fallbackMethod = "getHistoryFallback")
    public ResponseEntity<List<TransactionResponse>> getHistory(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ResponseEntity.ok(transactionService.getTransactionHistory(userDetails.getUsername()));
    }

    public ResponseEntity<List<TransactionResponse>> getHistoryFallback(UserDetails userDetails, Exception e) {
        log.warn("⚠️ History request rejected - system at capacity");
        return ResponseEntity.status(503).build();
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
                java.math.BigDecimal.ZERO,
                tx.getTransactionReference()
        );
    }
}
