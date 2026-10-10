package com.titan.titancorebanking.service.imple.otp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

// MODIFICATION: Secure-by-default. If no profile is passed (default) or 'prod' is passed, this strict generator takes over. No backdoor exists in this class.
@Component
@Profile("!test & !dev")
@Slf4j
public class SecureOtpGenerator implements OtpGenerator {

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public String generate(String username) {
        log.info("OTP Generated securely for user [{}]", username); // Logs event, but never the OTP payload
        return String.format("%06d", secureRandom.nextInt(1000000));
    }
}