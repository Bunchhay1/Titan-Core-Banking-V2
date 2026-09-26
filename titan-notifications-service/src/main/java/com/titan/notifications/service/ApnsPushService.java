package com.titan.notifications.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * Sends iOS push notifications via Apple Push Notification service (APNs HTTP/2 API).
 *
 * This service is the single place that fires APNs pushes in the whole system.
 * It fetches device tokens from titan-core-banking's internal endpoint, builds
 * the APNs payload, and delivers exactly ONE alert per user per event.
 *
 * Required config (in application-docker.properties / env vars):
 *   apns.key-id       — 10-char key ID from Apple Developer portal
 *   apns.team-id      — 10-char Team ID
 *   apns.private-key  — Content of the .p8 file (multiline OK in env var)
 *   apns.bundle-id    — e.g. com.titan.banking
 *   apns.sandbox      — true for dev builds, false for App Store / TestFlight
 *   core.banking.url  — internal URL of titan-core-banking e.g. http://titan-core-banking:8080
 */
@Slf4j
@Service
public class ApnsPushService {

    @Value("${apns.key-id:}")
    private String keyId;

    @Value("${apns.team-id:}")
    private String teamId;

    @Value("${apns.private-key:}")
    private String privateKeyPem;

    @Value("${apns.bundle-id:com.titan.banking}")
    private String bundleId;

    @Value("${apns.sandbox:true}")
    private boolean sandbox;

    @Value("${core.banking.url:http://titan-core-banking:8080}")
    private String coreBankingUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // Cached JWT — valid 1 hour per Apple spec; refresh after 55 min
    private volatile String cachedJwt;
    private volatile long   jwtCreatedAt = 0;

    /**
     * Look up ALL APNs device tokens for {@code username} from core-banking,
     * then send exactly one push per registered device.
     *
     * If APNs is not configured (missing key/team/privateKey), the call is a no-op
     * so the rest of notification processing is unaffected.
     *
     * @param username  the account owner's username (Account A or Account B)
     * @param title     notification title shown on the iOS lock screen
     * @param body      notification body text
     */
    public void pushToUser(String username, String title, String body, Map<String, Object> customData) {
        if (!isConfigured()) {
            log.debug("⚠️ APNs not configured — skipping push for user {}", username);
            return;
        }

        try {
            // ── 1. Fetch device tokens from core-banking internal endpoint ─────
            String url = coreBankingUrl + "/api/v1/notifications/internal/device-tokens/" + username;

            HttpRequest tokenRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> tokenResponse =
                    httpClient.send(tokenRequest, HttpResponse.BodyHandlers.ofString());

            if (tokenResponse.statusCode() != 200) {
                log.warn("⚠️ Could not fetch device tokens for user={}, status={}", username, tokenResponse.statusCode());
                return;
            }

            JsonNode tokens = objectMapper.readTree(tokenResponse.body());
            if (!tokens.isArray() || tokens.isEmpty()) {
                log.info("ℹ️ No device tokens registered for user={}", username);
                return;
            }

            // ── 2. Send one APNs push per device ──────────────────────────────
            for (JsonNode tokenNode : tokens) {
                String deviceToken = tokenNode.path("deviceToken").asText(null);
                String platform    = tokenNode.path("platform").asText("IOS");

                if (deviceToken == null || deviceToken.isBlank()) continue;
                if (!"IOS".equalsIgnoreCase(platform)) continue; // only iOS for now

                sendApnsPush(deviceToken, title, body, customData);
            }

        } catch (Exception e) {
            log.warn("⚠️ APNs push failed for user={}: {}", username, e.getMessage());
        }
    }

    // ── Internal: send one APNs push to a single device token ──────────────
    private void sendApnsPush(String deviceToken, String title, String body, Map<String, Object> customData) {
        try {
            String host = sandbox
                    ? "https://api.sandbox.push.apple.com"
                    : "https://api.push.apple.com";

            // Build the APNs JSON payload
            Map<String, Object> alert   = Map.of("title", title, "body", body);
            Map<String, Object> aps     = Map.of("alert", alert, "sound", "default", "badge", 1);
            
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("aps", aps);
            if (customData != null) {
                payload.put("data", customData);
            }
            String json = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(host + "/3/device/" + deviceToken))
                    .header("authorization", "bearer " + getJwt())
                    .header("apns-topic",    bundleId)
                    .header("apns-push-type","alert")
                    .header("apns-priority", "10")
                    .header("content-type",  "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                log.info("✅ APNs push delivered to device {}...", deviceToken.substring(0, 8));
            } else {
                log.error("❌ APNs error {} for device {}...: {}",
                        response.statusCode(), deviceToken.substring(0, 8), response.body());
            }
        } catch (Exception e) {
            log.error("❌ APNs push exception for device {}...: {}", deviceToken.substring(0, 8), e.getMessage());
        }
    }

    // ── Build or return cached APNs JWT (ES256, valid 1 hour) ──────────────
    private synchronized String getJwt() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        if (cachedJwt != null && (now - jwtCreatedAt) < 3300) {
            return cachedJwt;
        }

        String header  = base64url("{\"alg\":\"ES256\",\"kid\":\"" + keyId + "\"}");
        String claim   = base64url("{\"iss\":\"" + teamId + "\",\"iat\":" + now + "}");
        String unsigned = header + "." + claim;

        String cleanedKey = privateKeyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");

        byte[] keyBytes = Base64.getDecoder().decode(cleanedKey);
        java.security.spec.PKCS8EncodedKeySpec spec = new java.security.spec.PKCS8EncodedKeySpec(keyBytes);
        java.security.PrivateKey pk = java.security.KeyFactory.getInstance("EC").generatePrivate(spec);

        java.security.Signature sig = java.security.Signature.getInstance("SHA256withECDSA");
        sig.initSign(pk);
        sig.update(unsigned.getBytes(StandardCharsets.UTF_8));

        cachedJwt    = unsigned + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
        jwtCreatedAt = now;
        return cachedJwt;
    }

    private String base64url(String input) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(input.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isConfigured() {
        return keyId != null && !keyId.isBlank()
                && teamId != null && !teamId.isBlank()
                && privateKeyPem != null && !privateKeyPem.isBlank();
    }
}
