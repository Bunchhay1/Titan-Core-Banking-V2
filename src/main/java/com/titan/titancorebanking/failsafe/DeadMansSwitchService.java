package com.titan.titancorebanking.failsafe;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeadMansSwitchService {
    private final StringRedisTemplate redisTemplate;
    private final LockdownAuditRepository lockdownAuditRepository;

    private static final String HEARTBEAT_KEY = "dms:last_heartbeat";
    private static final String LOCKDOWN_FLAG  = "dms:lockdown_active";
    private static final String ADMIN_REVOKE_PREFIX = "dms:revoked:";
    private static final Duration HEARTBEAT_WINDOW = Duration.ofHours(24);

    @Value("${dms.executive.public-key-pem:}")
    private String executivePublicKeyPem;

    @Value("${dms.enabled:true}")
    private boolean enabled;

    private PublicKey cachedPublicKey;

    @PostConstruct
    public void initializeCryptography() {
        if (executivePublicKeyPem != null && !executivePublicKeyPem.isBlank()) {
            try {
                byte[] pubKeyBytes = Base64.getDecoder().decode(
                        executivePublicKeyPem.replaceAll("-----.*-----", "").replaceAll("\\s", ""));
                this.cachedPublicKey = KeyFactory.getInstance("RSA")
                        .generatePublic(new X509EncodedKeySpec(pubKeyBytes));
            } catch (Exception e) {
                log.error("DMS: Failed to initialize executive public key", e);
            }
        }
    }

    @Scheduled(fixedDelay = 300_000)
    public void checkHeartbeat() {
        if (!enabled) return;

        String lastHeartbeatStr = redisTemplate.opsForValue().get(HEARTBEAT_KEY);
        if (lastHeartbeatStr == null) return;

        Instant lastHeartbeat = Instant.ofEpochMilli(Long.parseLong(lastHeartbeatStr));
        Duration elapsed = Duration.between(lastHeartbeat, Instant.now());

        if (elapsed.compareTo(HEARTBEAT_WINDOW) > 0) {
            tripSwitch("Heartbeat timeout: " + elapsed.toHours() + " hours without executive sign-in");
        }
    }

    public void receiveHeartbeat(String timestampEpochMs, String base64Signature) throws Exception {
        if (!verifySignature(timestampEpochMs, base64Signature)) {
            throw new SecurityException("Invalid heartbeat signature");
        }

        long timestamp = Long.parseLong(timestampEpochMs);
        Instant signedAt = Instant.ofEpochMilli(timestamp);

        if (Duration.between(signedAt, Instant.now()).abs().compareTo(Duration.ofMinutes(5)) > 0) {
            throw new SecurityException("Heartbeat timestamp too old");
        }

        redisTemplate.opsForValue().set(HEARTBEAT_KEY, timestampEpochMs);
    }

    public void tripSwitch(String reason) {
        if (Boolean.TRUE.equals(redisTemplate.hasKey(LOCKDOWN_FLAG))) return;

        log.error("LOCKDOWN ACTIVATED: {}", reason);
        redisTemplate.opsForValue().set(LOCKDOWN_FLAG, Instant.now().toString());
        redisTemplate.opsForValue().set(ADMIN_REVOKE_PREFIX + "all", Instant.now().toString());

        lockdownAuditRepository.save(LockdownAuditEntry.builder()
                .reason(reason)
                .triggeredAt(java.time.LocalDateTime.now())
                .build());
    }

    public void manualTrip(String reason, String adminSignature) throws Exception {
        if (!verifySignature(reason, adminSignature)) {
            throw new SecurityException("Unauthorized manual trip attempt");
        }
        tripSwitch("MANUAL: " + reason);
    }

    public boolean isLockdownActive() {
        return Boolean.TRUE.equals(redisTemplate.hasKey(LOCKDOWN_FLAG));
    }

    private boolean verifySignature(String message, String base64Signature) throws Exception {
        if (cachedPublicKey == null) return true;

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(cachedPublicKey);
        sig.update(message.getBytes(StandardCharsets.UTF_8));
        return sig.verify(Base64.getDecoder().decode(base64Signature));
    }
}