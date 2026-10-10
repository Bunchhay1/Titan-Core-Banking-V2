package com.titan.titancorebanking.service.imple;

import com.titan.titancorebanking.service.imple.otp.OtpGenerator;
import com.titan.titancorebanking.exception.AccountLockedException;
import com.titan.titancorebanking.exception.InvalidOtpException;
import com.titan.titancorebanking.exception.RateLimitExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class OtpService {

    private final StringRedisTemplate redisTemplate;
    private final OtpGenerator otpGenerator;

    private static final String OTP_KEY_PREFIX = "OTP:AUTH:";
    private static final String ATTEMPT_KEY_PREFIX = "OTP:ATTEMPTS:";
    private static final String COOLDOWN_KEY_PREFIX = "OTP:COOLDOWN:";
    private static final String LOCKOUT_KEY_PREFIX = "OTP:LOCKOUT:";

    private static final Duration OTP_TTL = Duration.ofMinutes(5);
    private static final Duration COOLDOWN_TTL = Duration.ofSeconds(60);
    private static final Duration LOCKOUT_TTL = Duration.ofMinutes(15);

    private static final int MAX_ATTEMPTS = 3;

    public String generateOtp(String username) {
        String normalizedUser = sanitizeUsername(username);

        String lockoutKey = LOCKOUT_KEY_PREFIX + normalizedUser;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(lockoutKey))) {
            throw new AccountLockedException(String.format("Account is temporarily locked. Please try again in %d minutes.", LOCKOUT_TTL.toMinutes()));
        }

        String cooldownKey = COOLDOWN_KEY_PREFIX + normalizedUser;
        Boolean isAllowed = redisTemplate.opsForValue().setIfAbsent(cooldownKey, "LOCKED", COOLDOWN_TTL);
        if (Boolean.FALSE.equals(isAllowed)) {
            throw new RateLimitExceededException(String.format("Please wait %d seconds before requesting a new OTP.", COOLDOWN_TTL.getSeconds()));
        }

        String otp = otpGenerator.generate(normalizedUser);

        redisTemplate.opsForValue().set(OTP_KEY_PREFIX + normalizedUser, otp, OTP_TTL);
        redisTemplate.delete(ATTEMPT_KEY_PREFIX + normalizedUser);

        return otp;
    }

    public void validateOtp(String username, String otp) {
        if (otp == null || otp.isBlank()) {
            throw new InvalidOtpException("Invalid or expired OTP.");
        }

        String normalizedUser = sanitizeUsername(username);
        String lockoutKey = LOCKOUT_KEY_PREFIX + normalizedUser;

        if (Boolean.TRUE.equals(redisTemplate.hasKey(lockoutKey))) {
            throw new AccountLockedException("Account is temporarily locked. Please try again later.");
        }

        String cacheKey = OTP_KEY_PREFIX + normalizedUser;
        String attemptKey = ATTEMPT_KEY_PREFIX + normalizedUser;

        Long attempts = redisTemplate.opsForValue().increment(attemptKey);
        if (attempts != null && attempts == 1) {
            redisTemplate.expire(attemptKey, OTP_TTL);
        }

        if (attempts != null && attempts > MAX_ATTEMPTS) {
            redisTemplate.opsForValue().set(lockoutKey, "LOCKED", LOCKOUT_TTL);
            redisTemplate.delete(List.of(cacheKey, attemptKey));
            log.warn("SECURITY WARNING: User [{}] locked out for {} minutes after exceeding max OTP attempts.", normalizedUser, LOCKOUT_TTL.toMinutes());
            throw new AccountLockedException(String.format("Maximum OTP attempts exceeded. Account locked for %d minutes.", LOCKOUT_TTL.toMinutes()));
        }

        // Use get() instead of getAndDelete() to preserve the OTP for remaining attempts if validation fails
        String storedOtp = redisTemplate.opsForValue().get(cacheKey);

        if (storedOtp == null || !isOtpSecurelyEqual(storedOtp, otp)) {
            throw new InvalidOtpException("Invalid or expired OTP.");
        }

        // Atomically delete upon SUCCESSFUL validation to prevent double-spend race conditions
        Boolean wasDeleted = redisTemplate.delete(cacheKey);
        if (Boolean.FALSE.equals(wasDeleted)) {
            log.warn("Double-spend attempt detected for user [{}]", normalizedUser);
            throw new InvalidOtpException("OTP has already been used.");
        }

        // Clean up remaining tracking keys
        redisTemplate.delete(List.of(attemptKey, COOLDOWN_KEY_PREFIX + normalizedUser));

        log.info("OTP successfully validated and consumed for [{}]", normalizedUser);
    }

    private String sanitizeUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username is required");
        }
        return username.trim().toLowerCase();
    }

    private boolean isOtpSecurelyEqual(String storedOtp, String providedOtp) {
        byte[] storedBytes = storedOtp.getBytes(StandardCharsets.UTF_8);
        byte[] providedBytes = providedOtp.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(storedBytes, providedBytes);
    }
}