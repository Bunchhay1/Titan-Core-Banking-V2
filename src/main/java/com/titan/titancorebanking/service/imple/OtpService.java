package com.titan.titancorebanking.service.imple;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class OtpService {

    private final StringRedisTemplate redisTemplate;

    private static final String OTP_PREFIX = "auth:otp:";
    private static final String RATE_LIMIT_PREFIX = "auth:otp:ratelimit:";
    private static final Duration OTP_VALIDITY = Duration.ofMinutes(5);
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(1);

    public String generateOtp(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username is required for OTP generation");
        }

        // Prevent SMS/Email bombing: Max 1 OTP generation per minute per user
        String rateLimitKey = RATE_LIMIT_PREFIX + username;
        Boolean isRateLimited = redisTemplate.opsForValue().setIfAbsent(rateLimitKey, "LOCKED", RATE_LIMIT_WINDOW);
        if (Boolean.FALSE.equals(isRateLimited)) {
            throw new IllegalStateException("Please wait before requesting another OTP.");
        }

        String otp;
        if (username.startsWith("titan_test_") || username.startsWith("intellij_test")) {
            otp = "123456";
            log.debug("TEST ACCOUNT DETECTED: [{}] - Using fixed OTP", username);
        } else {
            // Cryptographically secure random 6-digit OTP
            otp = String.format("%06d", new java.security.SecureRandom().nextInt(1_000_000));
            log.debug("OTP Generated for [{}]", username); // NEVER log the actual OTP in production
        }

        // Store in Redis with strict 5-minute expiration
        redisTemplate.opsForValue().set(OTP_PREFIX + username, otp, OTP_VALIDITY);

        return otp;
    }

    public void validateOtp(String username, String otp) {
        if (otp == null || otp.isBlank()) {
            throw new IllegalArgumentException("OTP cannot be empty");
        }

        String redisKey = OTP_PREFIX + username;
        String storedOtp = redisTemplate.opsForValue().get(redisKey);

        if (storedOtp == null) {
            throw new IllegalArgumentException("OTP has expired or was never requested");
        }

        if (!storedOtp.equals(otp)) {
            throw new IllegalArgumentException("Invalid OTP!");
        }

        // Successful validation must immediately invalidate the OTP to prevent replay attacks
        redisTemplate.delete(redisKey);
        log.info("OTP Successfully Validated for [{}]", username);
    }
}