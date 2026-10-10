package com.titan.titancorebanking.service.imple.otp;

// MODIFICATION: Extracted generation logic into a Strategy interface to decouple environment-awareness from business logic (SRP & OCP).
public interface OtpGenerator {
    String generate(String username);
}