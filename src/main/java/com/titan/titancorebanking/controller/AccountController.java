package com.titan.titancorebanking.controller;

import com.titan.titancorebanking.dto.request.AccountRequest;
import com.titan.titancorebanking.dto.response.AccountResponse;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.service.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Hardened Account Controller.
 * Refactored to return strictly typed AccountResponse DTOs instead of raw JPA Entities.
 * This completely eliminates the risk of Hibernate LazyInitializationExceptions and
 * accidental leakage of nested User entity fields.
 */
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AccountResponse> createAccount(
            @RequestBody AccountRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {

        Account account = accountService.createAccount(request, userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(account));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<AccountResponse>> getMyAccounts(@AuthenticationPrincipal UserDetails userDetails) {
        List<AccountResponse> responses = accountService.getMyAccounts(userDetails.getUsername())
                .stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@accountSecurity.isAccountOwner(authentication, #id) or hasRole('ADMIN')")
    public ResponseEntity<AccountResponse> getAccountById(@PathVariable Long id) {
        return accountService.getAccountById(id)
                .map(this::toResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    private AccountResponse toResponse(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getAccountType().name(),
                account.getCurrency().name(),
                account.getBalance(),
                account.getOverdraftLimit(),
                account.getStatus().name(),
                account.getCreatedAt()
        );
    }
}