package com.titan.titancorebanking.service.imple;

import com.titan.titancorebanking.service.imple.otp.OtpGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

@Service
@Slf4j
@RequiredArgsConstructor
public class OtpService {

    private final StringRedisTemplate redisTemplate;
    private final OtpGenerator otpGenerator;

    private static final String OTP_KEY_PREFIX = "OTP:AUTH:";
    private static final String ATTEMPT_KEY_PREFIX = "OTP:ATTEMPTS:";

    // MODIFICATION: Added specific prefixes for rate limiting and security lockouts
    private static final String COOLDOWN_KEY_PREFIX = "OTP:COOLDOWN:";
    private static final String LOCKOUT_KEY_PREFIX = "OTP:LOCKOUT:";

    private static final Duration OTP_TTL = Duration.ofMinutes(5);
    // MODIFICATION: Defined precise durations for generation waits and penalty lockouts
    private static final Duration COOLDOWN_TTL = Duration.ofSeconds(60);
    private static final Duration LOCKOUT_TTL = Duration.ofMinutes(15);

    private static final int MAX_ATTEMPTS = 3;

    public String generateOtp(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username is required for OTP generation");
        }

        String lockoutKey = LOCKOUT_KEY_PREFIX + username;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(lockoutKey))) {
            throw new SecurityException("Account is temporarily locked due to excessive failed attempts. Please try again later.");
        }

        String cooldownKey = COOLDOWN_KEY_PREFIX + username;

        // MODIFICATION: Atomic check-and-set (SETNX) to prevent race conditions during concurrent generation requests
        Boolean isAllowed = redisTemplate.opsForValue().setIfAbsent(cooldownKey, "LOCKED", COOLDOWN_TTL);
        if (Boolean.FALSE.equals(isAllowed)) {
            throw new IllegalStateException("Please wait before requesting a new OTP.");
        }

        String otp = otpGenerator.generate(username);

        redisTemplate.opsForValue().set(OTP_KEY_PREFIX + username, otp, OTP_TTL);
        redisTemplate.delete(ATTEMPT_KEY_PREFIX + username);

        return otp;
    }

    public void validateOtp(String username, String otp) {
        if (otp == null || otp.isBlank()) {
            throw new IllegalArgumentException("OTP payload cannot be empty");
        }

        String lockoutKey = LOCKOUT_KEY_PREFIX + username;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(lockoutKey))) {
            throw new SecurityException("Account is temporarily locked. Please try again later.");
        }

        String cacheKey = OTP_KEY_PREFIX + username;
        String attemptKey = ATTEMPT_KEY_PREFIX + username;

        Long attempts = redisTemplate.opsForValue().increment(attemptKey);
        if (attempts != null && attempts == 1) {
            redisTemplate.expire(attemptKey, OTP_TTL);
        }

        if (attempts != null && attempts > MAX_ATTEMPTS) {
            // MODIFICATION: Apply the security lockout duration before purging the OTP
            redisTemplate.opsForValue().set(lockoutKey, "LOCKED", LOCKOUT_TTL);
            redisTemplate.delete(cacheKey);
            redisTemplate.delete(attemptKey);
            log.warn("SECURITY WARNING: User [{}] locked out for {} minutes after exceeding max OTP attempts.", username, LOCKOUT_TTL.toMinutes());
            throw new SecurityException("Maximum OTP attempts exceeded. Account locked for 15 minutes.");
        }

        String storedOtp = redisTemplate.opsForValue().get(cacheKey);

        if (storedOtp == null) {
            throw new IllegalArgumentException("Invalid or expired OTP!");
        }

        byte[] storedBytes = storedOtp.getBytes(StandardCharsets.UTF_8);
        byte[] providedBytes = otp.getBytes(StandardCharsets.UTF_8);

        if (!MessageDigest.isEqual(storedBytes, providedBytes)) {
            throw new IllegalArgumentException("Invalid OTP!");
        }

        redisTemplate.delete(cacheKey);
        redisTemplate.delete(attemptKey);

        // MODIFICATION: Optionally clear the cooldown immediately upon successful verification to allow uninterrupted subsequent flows
        redisTemplate.delete(COOLDOWN_KEY_PREFIX + username);

        log.info("OTP successfully validated and consumed for [{}]", username);
    }
}