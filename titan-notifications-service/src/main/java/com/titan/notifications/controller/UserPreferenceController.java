package com.titan.notifications.controller;

import com.titan.notifications.model.UserPreference;
import com.titan.notifications.service.UserPreferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for notification preferences.
 *
 * GET  /api/preferences/{userId}  — get preferences for a user
 * PUT  /api/preferences/{userId}  — update preferences for a user
 *
 * Called by the iOS Notifications → Preferences tab.
 */
@RestController
@RequestMapping("/api/preferences")
@Slf4j
@RequiredArgsConstructor
public class UserPreferenceController {

    private final UserPreferenceService preferenceService;

    /**
     * GET /api/preferences/{userId}
     * Returns the current preferences for a user.
     * Creates default preferences if none exist.
     */
    @GetMapping("/{userId}")
    public ResponseEntity<UserPreferenceDto> getPreference(@PathVariable String userId) {
        log.info("GET preferences for userId={}", userId);
        UserPreference pref = preferenceService.getPreferences(userId);
        return ResponseEntity.ok(toDto(pref));
    }

    /**
     * PUT /api/preferences/{userId}
     * Update preferences for a user (email, SMS, push toggles, locale).
     */
    @PutMapping("/{userId}")
    public ResponseEntity<UserPreferenceDto> updatePreference(
            @PathVariable String userId,
            @RequestBody UserPreferenceDto dto) {
        log.info("PUT preferences for userId={}, emailEnabled={}, smsEnabled={}, pushEnabled={}",
                userId, dto.emailEnabled(), dto.smsEnabled(), dto.pushEnabled());

        UserPreference pref = preferenceService.getPreferences(userId);
        pref.setTransactionAlertsEnabled(dto.emailEnabled() || dto.smsEnabled() || dto.pushEnabled());
        pref.setMarketingOptIn(dto.marketingEnabled());
        pref.setPreferredLocale(dto.locale() != null ? dto.locale() : "en");

        // Update email/phone only if provided (non-blank)
        if (dto.email() != null && !dto.email().isBlank()) {
            pref.setEmail(dto.email());
        }
        if (dto.smsNumber() != null && !dto.smsNumber().isBlank()) {
            pref.setSmsNumber(dto.smsNumber());
        }

        UserPreference saved = preferenceService.save(pref);
        return ResponseEntity.ok(toDto(saved));
    }

    // ── DTO ─────────────────────────────────────────────────────────────────

    record UserPreferenceDto(
            String userId,
            boolean emailEnabled,
            boolean smsEnabled,
            boolean pushEnabled,
            boolean marketingEnabled,
            String locale,
            String email,
            String smsNumber
    ) {}

    private UserPreferenceDto toDto(UserPreference pref) {
        // emailEnabled / smsEnabled are derived from transactionAlertsEnabled
        // (UserPreference doesn't have separate per-channel flags yet)
        boolean alertsEnabled = pref.isTransactionAlertsEnabled();
        return new UserPreferenceDto(
                pref.getUserId(),
                alertsEnabled,          // emailEnabled
                alertsEnabled,          // smsEnabled
                alertsEnabled,          // pushEnabled
                pref.isMarketingOptIn(),
                pref.getPreferredLocale(),
                pref.getEmail(),
                pref.getSmsNumber()
        );
    }
}
