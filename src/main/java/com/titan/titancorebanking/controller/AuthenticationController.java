package com.titan.titancorebanking.controller;

import com.titan.titancorebanking.dto.request.LoginRequest;
import com.titan.titancorebanking.dto.request.RegisterRequest;
import com.titan.titancorebanking.dto.response.AuthResponse;
import com.titan.titancorebanking.service.imple.AuthenticationService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Authentication API", description = "Registration and JWT issuance")
public class AuthenticationController {

    private final AuthenticationService authenticationService;

    @PostMapping("/register")
    @Operation(summary = "Register a new customer account")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        log.info("New registration attempt for username: {}", request.username());
        return ResponseEntity.ok(authenticationService.register(request));
    }

    @PostMapping("/login")
    @RateLimiter(name = "login-attempts", fallbackMethod = "loginRateLimitFallback")
    @Operation(summary = "Authenticate and retrieve a JWT")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authenticationService.login(request));
    }

    public ResponseEntity<AuthResponse> loginRateLimitFallback(LoginRequest request, Exception e) {
        log.warn("Rate limit exceeded for login attempts on username: {}", request.username());
        throw new IllegalStateException("Too many login attempts. Please try again later.");
    }
}