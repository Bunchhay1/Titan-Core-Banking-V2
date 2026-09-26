package com.titan.titancorebanking.service;

import com.titan.titancorebanking.dto.request.AtmGenerateRequest;
import com.titan.titancorebanking.dto.request.AtmRedeemRequest;
import com.titan.titancorebanking.dto.response.AtmCodeResponse;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.AtmCode;
import com.titan.titancorebanking.model.AtmCode.AtmCodeStatus;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.enums.TransactionStatus;
import com.titan.titancorebanking.enums.TransactionType;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.AtmCodeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;

/**
 * ATM Cardless Withdrawal Service
 *
 * Business Rules:
 *  - Customer must own the account and supply the correct PIN.
 *  - Balance is checked at generation but NOT reserved; it is deducted atomically at redemption.
 *  - Only one PENDING code allowed per account — generating a new one cancels the previous.
 *  - Codes expire after `atm.code.expiry-minutes` minutes (default 10, env: ATM_CODE_EXPIRY_MINUTES).
 *  - Each code is single-use.
 *
 * 12-Digit Code Generation:
 *  Uses SecureRandom. First digit is 1-9 (never zero) so the code always
 *  displays as a full 12-digit number on the ATM screen.
 */
@Service
@Slf4j
public class AtmCodeService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Injected from atm.code.expiry-minutes property (env: ATM_CODE_EXPIRY_MINUTES). */
    @Value("${atm.code.expiry-minutes:10}")
    private int codeExpiryMinutes;

    private final AtmCodeRepository atmCodeRepository;
    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final TransactionAuditService auditService;
    private final EventPublisherService eventPublisherService;

    // Explicit constructor — needed because @Value cannot be used with @RequiredArgsConstructor
    public AtmCodeService(AtmCodeRepository atmCodeRepository,
                          AccountRepository accountRepository,
                          PasswordEncoder passwordEncoder,
                          TransactionAuditService auditService,
                          EventPublisherService eventPublisherService) {
        this.atmCodeRepository     = atmCodeRepository;
        this.accountRepository     = accountRepository;
        this.passwordEncoder       = passwordEncoder;
        this.auditService          = auditService;
        this.eventPublisherService = eventPublisherService;
    }

    // =========================================================================
    // 1. GENERATE — customer requests a withdrawal code via mobile app
    // =========================================================================

    @Transactional
    public AtmCodeResponse generateCode(AtmGenerateRequest request, String currentUsername) {

        // 1. Verify account ownership
        Account account = accountRepository.findByAccountNumber(request.accountNumber())
                .orElseThrow(() -> new RuntimeException("Account not found: " + request.accountNumber()));

        if (!account.getUser().getUsername().equals(currentUsername)) {
            throw new RuntimeException("⛔ You do not own this account");
        }

        // 2. Verify PIN
        if (!passwordEncoder.matches(request.pin(), account.getUser().getPin())) {
            throw new RuntimeException("❌ Invalid PIN");
        }

        // 3. Soft balance check (hard deduction happens at redeem)
        if (account.getBalance().compareTo(request.amount()) < 0) {
            throw new RuntimeException("❌ Insufficient balance for this ATM withdrawal");
        }

        // 4. Cancel any existing PENDING codes for this account
        List<AtmCode> pending = atmCodeRepository.findByAccount_IdAndStatus(
                account.getId(), AtmCodeStatus.PENDING);
        if (!pending.isEmpty()) {
            pending.forEach(c -> c.setStatus(AtmCodeStatus.CANCELLED));
            atmCodeRepository.saveAll(pending);
            log.info("Cancelled {} existing pending ATM code(s) for account {}",
                    pending.size(), account.getAccountNumber());
        }

        // 5. Generate a unique, cryptographically secure 12-digit code
        String code = generateSecure12DigitCode();

        // 6. Persist
        AtmCode atmCode = AtmCode.builder()
                .code(code)
                .account(account)
                .amount(request.amount())
                .status(AtmCodeStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(codeExpiryMinutes))
                .build();

        atmCode = atmCodeRepository.save(atmCode);
        log.info("✅ ATM code generated for account={} expires={}",
                account.getAccountNumber(), atmCode.getExpiresAt());

        return toResponse(atmCode,
                "Code generated. Valid for " + codeExpiryMinutes + " minutes. Do not share this code.");
    }

    // =========================================================================
    // 2. REDEEM — ATM terminal verifies the code and dispenses cash
    // =========================================================================

    @Transactional
    public AtmCodeResponse redeemCode(AtmRedeemRequest request) {

        // 1. Pessimistic lock to prevent concurrent double-redemption
        AtmCode atmCode = atmCodeRepository.findByCodeWithLock(request.code())
                .orElseThrow(() -> new RuntimeException("❌ Invalid ATM code"));

        // 2. Status guard
        switch (atmCode.getStatus()) {
            case USED      -> throw new RuntimeException("❌ This code has already been used");
            case EXPIRED   -> throw new RuntimeException("❌ This code has expired");
            case CANCELLED -> throw new RuntimeException("❌ This code was cancelled");
            case PENDING   -> { /* ok */ }
        }

        // 3. Expiry check (belt-and-suspenders in case scheduler hasn't run yet)
        if (LocalDateTime.now().isAfter(atmCode.getExpiresAt())) {
            atmCode.setStatus(AtmCodeStatus.EXPIRED);
            atmCodeRepository.save(atmCode);
            throw new RuntimeException("❌ This code has expired");
        }

        // 4. Lock account row and verify balance
        Account account = accountRepository.findByAccountNumberWithLock(
                        atmCode.getAccount().getAccountNumber())
                .orElseThrow(() -> new RuntimeException("Account not found"));

        if (account.getBalance().compareTo(atmCode.getAmount()) < 0) {
            throw new RuntimeException("❌ Insufficient funds at time of redemption");
        }

        // 5. Deduct balance
        account.setBalance(account.getBalance().subtract(atmCode.getAmount()));
        accountRepository.save(account);

        // 6. Mark code USED
        atmCode.setStatus(AtmCodeStatus.USED);
        atmCode.setRedeemedAt(LocalDateTime.now());
        atmCode.setAtmTerminalId(request.terminalId());
        atmCodeRepository.save(atmCode);

        // 7. Audit trail
        Transaction tx = auditService.saveAuditLog(
                account, null, atmCode.getAmount(),
                TransactionType.WITHDRAWAL, TransactionStatus.SUCCESS,
                "ATM cardless withdrawal | code=" + atmCode.getCode()
                        + (request.terminalId() != null ? " | terminal=" + request.terminalId() : ""));

        // 8. Publish Kafka event → notifications service sends push alert
        eventPublisherService.publishTransactionCompletedEvent(tx);

        log.info("💵 ATM withdrawal OK | account={} amount={} terminal={}",
                account.getAccountNumber(), atmCode.getAmount(), request.terminalId());

        return toResponse(atmCode, "Withdrawal successful. Cash has been dispensed.");
    }

    // =========================================================================
    // 3. CANCEL — customer cancels a pending code before use
    // =========================================================================

    @Transactional
    public AtmCodeResponse cancelCode(String code, String currentUsername) {

        AtmCode atmCode = atmCodeRepository.findByCode(code)
                .orElseThrow(() -> new RuntimeException("❌ ATM code not found"));

        if (!atmCode.getAccount().getUser().getUsername().equals(currentUsername)) {
            throw new RuntimeException("⛔ You do not own this code");
        }
        if (atmCode.getStatus() != AtmCodeStatus.PENDING) {
            throw new RuntimeException("Only PENDING codes can be cancelled (current: " + atmCode.getStatus() + ")");
        }

        atmCode.setStatus(AtmCodeStatus.CANCELLED);
        atmCodeRepository.save(atmCode);

        log.info("🚫 ATM code cancelled by user={}", currentUsername);
        return toResponse(atmCode, "ATM code cancelled successfully.");
    }

    // =========================================================================
    // 4. STATUS — customer checks a code they generated
    // =========================================================================

    @Transactional(readOnly = true)
    public AtmCodeResponse getCodeStatus(String code, String currentUsername) {

        AtmCode atmCode = atmCodeRepository.findByCode(code)
                .orElseThrow(() -> new RuntimeException("❌ ATM code not found"));

        if (!atmCode.getAccount().getUser().getUsername().equals(currentUsername)) {
            throw new RuntimeException("⛔ You do not own this code");
        }

        String message = switch (atmCode.getStatus()) {
            case PENDING   -> "Code is valid. Expires at: " + atmCode.getExpiresAt();
            case USED      -> "Code was successfully redeemed.";
            case EXPIRED   -> "Code has expired.";
            case CANCELLED -> "Code was cancelled.";
        };

        return toResponse(atmCode, message);
    }

    // =========================================================================
    // 5. SCHEDULED EXPIRY SWEEP — every 60 seconds
    // =========================================================================

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void expireOldCodes() {
        int expired = atmCodeRepository.expirePendingCodes(LocalDateTime.now());
        if (expired > 0) {
            log.info("⏰ Expired {} stale ATM code(s)", expired);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String generateSecure12DigitCode() {
        String code;
        int attempts = 0;
        do {
            if (++attempts > 10) {
                throw new IllegalStateException("Unable to generate a unique ATM code after 10 attempts");
            }
            // First digit 1-9 so the number always has 12 visible digits on ATM display
            int first = 1 + SECURE_RANDOM.nextInt(9);
            StringBuilder sb = new StringBuilder(12).append(first);
            for (int i = 1; i < 12; i++) {
                sb.append(SECURE_RANDOM.nextInt(10));
            }
            code = sb.toString();
        } while (atmCodeRepository.findByCode(code).isPresent());

        return code;
    }

    private AtmCodeResponse toResponse(AtmCode atmCode, String message) {
        return new AtmCodeResponse(
                atmCode.getId(),
                atmCode.getCode(),
                atmCode.getAccount().getAccountNumber(),
                atmCode.getAmount(),
                atmCode.getStatus().name(),
                atmCode.getExpiresAt(),
                atmCode.getRedeemedAt(),
                atmCode.getCreatedAt(),
                message
        );
    }
}
