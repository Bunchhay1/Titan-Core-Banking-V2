package com.titan.titancorebanking.controller;

import com.titan.titancorebanking.model.DeviceToken;
import com.titan.titancorebanking.service.DeviceTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;

    record DeviceTokenRequest(String deviceToken, String platform) {}

    /**
     * POST /api/v1/notifications/device-token
     * iOS app calls this after login to register the APNs token.
     * Requires JWT bearer token in Authorization header.
     */
    @PostMapping("/device-token")
    public ResponseEntity<Void> registerDeviceToken(
            @RequestBody DeviceTokenRequest request,
            Authentication auth) {

        String username = auth.getName();
        deviceTokenService.registerToken(username, request.deviceToken(), request.platform());
        return ResponseEntity.ok().build();
    }

    /**
     * GET /api/v1/notifications/internal/device-tokens/{username}
     *
     * Internal endpoint — called by titan-notifications-service so it can
     * look up APNs device tokens for a user and fire the push itself.
     *
     * Only accessible within the Docker network (not exposed externally).
     * Returns a list of { deviceToken, platform } objects.
     */
    @GetMapping("/internal/device-tokens/{username}")
    public ResponseEntity<List<DeviceTokenInfo>> getDeviceTokens(@PathVariable String username) {
        List<DeviceToken> tokens = deviceTokenService.getTokensForUsername(username);
        List<DeviceTokenInfo> result = tokens.stream()
                .map(t -> new DeviceTokenInfo(t.getDeviceToken(), t.getPlatform()))
                .toList();
        return ResponseEntity.ok(result);
    }

    record DeviceTokenInfo(String deviceToken, String platform) {}
}
