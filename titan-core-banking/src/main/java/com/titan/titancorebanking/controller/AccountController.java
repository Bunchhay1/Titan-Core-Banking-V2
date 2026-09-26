package com.titan.titancorebanking.controller;

import com.titan.titancorebanking.dto.request.AccountRequest;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.service.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    // ✅ Endpoint: Create Account
    @PostMapping
    public ResponseEntity<Account> createAccount(
            @RequestBody AccountRequest request,
            @AuthenticationPrincipal UserDetails userDetails // ទាញយក User ដែលកំពុង Login
    ) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(accountService.createAccount(request, userDetails.getUsername()));
    }

    // ✅ Endpoint: Get My Accounts
    @GetMapping
    public ResponseEntity<List<Account>> getMyAccounts(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(accountService.getMyAccounts(userDetails.getUsername()));
    }

    // ✅ Endpoint: Get Account By ID (internal — used by titan-loans-service)
    @GetMapping("/{id}")
    public ResponseEntity<Account> getAccountById(@PathVariable Long id) {
        return accountService.getAccountById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}