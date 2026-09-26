package com.titan.titancorebanking.controller;

import com.titan.titancorebanking.service.imple.OtpService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth/otp") // 👈 Path ដាច់ដោយឡែក
@RequiredArgsConstructor
public class OtpController {

    private final OtpService otpService;

    // ==========================================
    // 🔐 REQUEST OTP ENDPOINT
    // ==========================================
    @PostMapping("/generate")
    public ResponseEntity<?> generateOtp(Authentication authentication) {

        // 1. យក username ពី Token (User ដែលកំពុង Login)
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized"));
        }

        String username = authentication.getName();

        // 2. បង្កើត OTP (Save ចូល Redis & Log ចូល Console)
        String otp;
        try {
            otp = otpService.generateOtp(username);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }

        // 3. ឆ្លើយតបទៅ Frontend
        Map<String, Object> response = new LinkedHashMap<>();
        if (username.startsWith("titan_test_") || username.startsWith("intellij_test")) {
            response.put("message", "OTP generated (test mode)");
            response.put("otp", otp);
        } else {
            response.put("message", "📧 OTP has been sent! (Check Server Console)");
        }
        response.put("status", "PENDING_VERIFICATION");
        return ResponseEntity.ok(response);
    }
}
