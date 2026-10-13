package com.titan.titancorebanking.controller;

import com.titan.titancorebanking.dto.request.AccountRequest;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Account Management", description = "Endpoints for managing user bank accounts and balance inquiries")
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    @Operation(summary = "Create Account", description = "Opens a new account for the authenticated user")
    public ResponseEntity<Account> createAccount(
            @Valid @RequestBody AccountRequest request,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        log.info("Creating account for user: {}", userDetails.getUsername());
        Account createdAccount = accountService.createAccount(request, userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(createdAccount);
    }

    @GetMapping
    @Operation(summary = "Get User Accounts", description = "Retrieves all accounts owned by the authenticated user")
    public ResponseEntity<List<Account>> getMyAccounts(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(accountService.getMyAccounts(userDetails.getUsername()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get Account by ID", description = "Protected by IDOR checks; only the owner or an ADMIN can view details")
    @PreAuthorize("@accountSecurity.isAccountOwner(authentication, #id) or hasRole('ADMIN')")
    public ResponseEntity<Account> getAccountById(@PathVariable Long id) {
        return accountService.getAccountById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/balance/{accountNumber}")
    @Operation(summary = "Get Account Balance", description = "Returns aggregated balance across all partitioned buckets")
    public ResponseEntity<BigDecimal> getBalance(
            @PathVariable String accountNumber,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ResponseEntity.ok(accountService.getBalance(accountNumber));
    }
}