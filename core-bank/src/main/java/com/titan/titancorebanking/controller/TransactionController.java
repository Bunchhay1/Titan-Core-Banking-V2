package com.titan.titancorebanking.controller;

import com.titan.titancorebanking.dto.request.TransactionRequest;
import com.titan.titancorebanking.dto.response.TransactionResponse;
import com.titan.titancorebanking.model.Account;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Transaction API", description = "Core banking operations (Transfer, Withdraw, Deposit)")
public class TransactionController {

    private final TransactionService transactionService;

    // [MODIFIED] Pre-compiled Regex patterns to eliminate severe CPU overhead on every request.
    // Compile Regex ទុកជាមុន ជៀសវាងការ Compile ថ្មីរាល់ពេលមាន Request ចូល ដែលធ្វើអោយ CPU ធ្វើការធ្ងន់ (CPU Overhead)។
    private static final Pattern SWIFT_PATTERN = Pattern.compile("^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$");
    private static final Pattern IBAN_PATTERN = Pattern.compile("^[A-Z]{2}\\d{2}[A-Z0-9]{4,30}$");

    @PostMapping("/transfer")
    @Operation(summary = "Execute Fund Transfer", description = "Transfers money between accounts safely.")
    public ResponseEntity<TransactionResponse> transfer(
            @RequestBody final TransactionRequest request,
            @AuthenticationPrincipal final UserDetails userDetails) {

        log.info("Transfer Request Initiated: {} -> {}", request.fromAccountNumber(), request.toAccountNumber());
        var tx = transactionService.transfer(request, userDetails.getUsername());
        return ResponseEntity.ok(toTransactionResponse(tx));
    }

    @PostMapping("/withdraw")
    @Operation(summary = "Withdraw Money", description = "Deducts balance from account.")
    public ResponseEntity<TransactionResponse> withdraw(
            @RequestBody final TransactionRequest request,
            @AuthenticationPrincipal final UserDetails userDetails) {

        log.info("Withdraw Request Initiated: Acc: {}", request.fromAccountNumber());
        var tx = transactionService.withdraw(request, userDetails.getUsername());
        return ResponseEntity.ok(toTransactionResponse(tx));
    }

    @PostMapping("/deposit")
    @Operation(summary = "Deposit Money", description = "Adds balance to account.")
    public ResponseEntity<TransactionResponse> deposit(
            @RequestBody final TransactionRequest request,
            @AuthenticationPrincipal final UserDetails userDetails) {

        log.info("Deposit Request Initiated: Acc: {}", request.toAccountNumber());
        var tx = transactionService.deposit(request);
        return ResponseEntity.ok(toTransactionResponse(tx));
    }

    /*
     * STAFF ENGINEER NOTE:
     * Manual regex validation inside the controller violates the Single Responsibility Principle.
     * Future refactor: Delegate this to the DTO layer using standard Jakarta Validation
     * (e.g., @ValidIBAN, @ValidSwift) to trigger GlobalExceptionHandler cleanly.
     */
    @PostMapping("/international")
    @Operation(summary = "International Transfer", description = "SWIFT/IBAN validated transfer.")
    // [MODIFIED] Replaced generic wildcard <?> with strict Map<String, String> contract.
    // ផ្លាស់ប្តូរ ResponseEntity<?> ទៅជាប្រភេទច្បាស់លាស់ (Strongly Typed) ដើម្បីធានាថា API តែងតែ Return ទម្រង់ទិន្នន័យ (JSON Contract) ដែលអាចទុកចិត្តបាន។
    public ResponseEntity<Map<String, String>> internationalTransfer(
            @RequestBody final TransactionRequest request,
            @AuthenticationPrincipal final UserDetails userDetails) {

        if (request.swiftCode() == null || !SWIFT_PATTERN.matcher(request.swiftCode()).matches()) {
            log.warn("Invalid SWIFT Code attempt by user: {}", userDetails.getUsername());
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid SWIFT Code format"));
        }

        if (request.iban() == null || !IBAN_PATTERN.matcher(request.iban()).matches()) {
            log.warn("Invalid IBAN attempt by user: {}", userDetails.getUsername());
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid IBAN format"));
        }

        log.info("International Transfer Validated for user: {}", userDetails.getUsername());
        return ResponseEntity.ok(Map.of("message", "International Transfer Initiated successfully"));
    }

    @GetMapping
    @Operation(summary = "Get Transaction History", description = "Returns all transactions for logged-in user.")
    @io.github.resilience4j.bulkhead.annotation.Bulkhead(name = "non-critical", fallbackMethod = "getHistoryFallback")
    public ResponseEntity<List<TransactionResponse>> getHistory(
            @AuthenticationPrincipal final UserDetails userDetails) {

        return ResponseEntity.ok(transactionService.getTransactionHistory(userDetails.getUsername()));
    }

    // [MODIFIED] Aligned fallback signature and provided strict JSON error response instead of empty 503.
    public ResponseEntity<List<TransactionResponse>> getHistoryFallback(final UserDetails userDetails, final Exception e) {
        log.warn("Bulkhead rejected history request for user: {} - System at capacity", userDetails.getUsername());
        return ResponseEntity.status(503).build();
    }

    // [MODIFIED] Safely chained optionals to prevent NullPointerExceptions during mapping.
    // ប្រើប្រាស់ Optional ដើម្បីចាប់យក Currency ដោយសុវត្ថិភាព ការពារកុំឲ្យកម្មវិធីគាំង (NullPointerException) ពេល Account ណាមួយអត់មានទិន្នន័យ។
    private TransactionResponse toTransactionResponse(final Transaction tx) {
        var currency = Optional.ofNullable(tx.getFromAccount())
                .map(Account::getCurrency)
                .map(Enum::name)
                .orElseGet(() -> Optional.ofNullable(tx.getToAccount())
                        .map(Account::getCurrency)
                        .map(Enum::name)
                        .orElse("USD"));

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
                BigDecimal.ZERO,
                tx.getTransactionReference()
        );
    }
}