package com.titan.titancorebanking.service;

import com.titan.titancorebanking.model.DeviceToken;
import com.titan.titancorebanking.model.User;
import com.titan.titancorebanking.repository.DeviceTokenRepository;
import com.titan.titancorebanking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Manages APNs device token registration and lookup.
 *
 * core-banking ONLY stores tokens — it does NOT send push notifications.
 * All push notification delivery belongs in titan-notifications-service,
 * which calls GET /api/v1/notifications/internal/device-tokens/{username}
 * to fetch tokens and fires APNs pushes itself.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceTokenService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final UserRepository userRepository;

    /**
     * Save (or update) an APNs device token for the logged-in user.
     * Called by the iOS app after login via POST /api/v1/notifications/device-token.
     */
    @Transactional
    public void registerToken(String username, String token, String platform) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));

        // Upsert — avoid duplicates
        if (deviceTokenRepository.existsByUserIdAndDeviceToken(user.getId(), token)) {
            log.info("📲 Device token already registered for user {}", username);
            return;
        }

        DeviceToken dt = DeviceToken.builder()
                .user(user)
                .deviceToken(token)
                .platform(platform != null ? platform : "IOS")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        deviceTokenRepository.save(dt);
        log.info("✅ Device token registered for user {} ({}...)", username, token.substring(0, 8));
    }

    /**
     * Return all device tokens registered for a username.
     * Used by the internal endpoint called by titan-notifications-service.
     */
    public List<DeviceToken> getTokensForUsername(String username) {
        return userRepository.findByUsername(username)
                .map(user -> deviceTokenRepository.findByUserId(user.getId()))
                .orElse(java.util.Collections.emptyList());
    }
}
