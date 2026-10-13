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

@Service
@Slf4j
public class AtmCodeService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Value("${atm.code.expiry-minutes:10}")
    private int codeExpiryMinutes;

    private final AtmCodeRepository atmCodeRepository;
    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final TransactionAuditService auditService;
    private final EventPublisherService eventPublisherService;

    public AtmCodeService(AtmCodeRepository atmCodeRepository,
                          AccountRepository accountRepository,
                          PasswordEncoder passwordEncoder,
                          TransactionAuditService auditService,
                          EventPublisherService eventPublisherService) {
        this.atmCodeRepository = atmCodeRepository;
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.eventPublisherService = eventPublisherService;
    }

    @Transactional
    public AtmCodeResponse generateCode(AtmGenerateRequest request, String currentUsername) {
        Account account = accountRepository.findByAccountNumber(request.accountNumber())
                .orElseThrow(() -> new RuntimeException("Account not found: " + request.accountNumber()));

        if (!account.getUser().getUsername().equals(currentUsername)) {
            throw new SecurityException("You do not own this account");
        }

        if (!passwordEncoder.matches(request.pin(), account.getUser().getPin())) {
            throw new SecurityException("Invalid PIN");
        }

        if (account.getBalance().compareTo(request.amount()) < 0) {
            throw new RuntimeException("Insufficient balance for this ATM withdrawal");
        }

        List<AtmCode> pending = atmCodeRepository.findByAccount_IdAndStatus(
                account.getId(), AtmCodeStatus.PENDING);

        if (!pending.isEmpty()) {
            pending.forEach(c -> c.setStatus(AtmCodeStatus.CANCELLED));
            atmCodeRepository.saveAll(pending);
            log.info("Cancelled {} existing pending ATM code(s) for account {}", pending.size(), account.getAccountNumber());
        }

        String code = generateSecure12DigitCode();

        AtmCode atmCode = AtmCode.builder()
                .code(code)
                .account(account)
                .amount(request.amount())
                .status(AtmCodeStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(codeExpiryMinutes))
                .build();

        atmCode = atmCodeRepository.save(atmCode);
        log.info("ATM code generated for account={} expires={}", account.getAccountNumber(), atmCode.getExpiresAt());

        return toResponse(atmCode, "Code generated. Valid for " + codeExpiryMinutes + " minutes. Do not share this code.");
    }

    @Transactional
    public AtmCodeResponse redeemCode(AtmRedeemRequest request) {
        // STAFF ENGINEER FIX: Lock Upgrade Pattern to prevent Deadlocks
        // 1. Read without lock to identify the parent Account
        AtmCode unverifiedCode = atmCodeRepository.findByCode(request.code())
                .orElseThrow(() -> new RuntimeException("Invalid ATM code"));

        // 2. Lock the higher-hierarchy resource FIRST (The Account)
        Account account = accountRepository.findByAccountNumberWithLock(
                        unverifiedCode.getAccount().getAccountNumber())
                .orElseThrow(() -> new RuntimeException("Account not found"));

        // 3. Lock the lower-hierarchy resource SECOND (The AtmCode) and re-verify state
        AtmCode atmCode = atmCodeRepository.findByCodeWithLock(request.code())
                .orElseThrow(() -> new RuntimeException("Invalid ATM code"));

        switch (atmCode.getStatus()) {
            case USED      -> throw new RuntimeException("This code has already been used");
            case EXPIRED   -> throw new RuntimeException("This code has expired");
            case CANCELLED -> throw new RuntimeException("This code was cancelled");
            case PENDING   -> { /* Proceed */ }
        }

        if (LocalDateTime.now().isAfter(atmCode.getExpiresAt())) {
            atmCode.setStatus(AtmCodeStatus.EXPIRED);
            atmCodeRepository.save(atmCode);
            throw new RuntimeException("This code has expired");
        }

        if (account.getBalance().compareTo(atmCode.getAmount()) < 0) {
            throw new RuntimeException("Insufficient funds at time of redemption");
        }

        account.setBalance(account.getBalance().subtract(atmCode.getAmount()));
        accountRepository.save(account);

        atmCode.setStatus(AtmCodeStatus.USED);
        atmCode.setRedeemedAt(LocalDateTime.now());
        atmCode.setAtmTerminalId(request.terminalId());
        atmCodeRepository.save(atmCode);

        Transaction tx = auditService.saveAuditLog(
                account, null, atmCode.getAmount(),
                TransactionType.WITHDRAWAL, TransactionStatus.SUCCESS,
                "ATM cardless withdrawal | terminal=" + request.terminalId());

        eventPublisherService.publishTransactionCompletedEvent(tx);

        log.info("ATM withdrawal OK | account={} amount={} terminal={}",
                account.getAccountNumber(), atmCode.getAmount(), request.terminalId());

        return toResponse(atmCode, "Withdrawal successful. Cash has been dispensed.");
    }

    @Transactional
    public AtmCodeResponse cancelCode(String code, String currentUsername) {
        AtmCode atmCode = atmCodeRepository.findByCode(code)
                .orElseThrow(() -> new RuntimeException("ATM code not found"));

        if (!atmCode.getAccount().getUser().getUsername().equals(currentUsername)) {
            throw new SecurityException("You do not own this code");
        }

        if (atmCode.getStatus() != AtmCodeStatus.PENDING) {
            throw new RuntimeException("Only PENDING codes can be cancelled");
        }

        atmCode.setStatus(AtmCodeStatus.CANCELLED);
        atmCodeRepository.save(atmCode);
        return toResponse(atmCode, "ATM code cancelled successfully.");
    }

    @Transactional(readOnly = true)
    public AtmCodeResponse getCodeStatus(String code, String currentUsername) {
        AtmCode atmCode = atmCodeRepository.findByCode(code)
                .orElseThrow(() -> new RuntimeException("ATM code not found"));

        if (!atmCode.getAccount().getUser().getUsername().equals(currentUsername)) {
            throw new SecurityException("You do not own this code");
        }

        String message = switch (atmCode.getStatus()) {
            case PENDING   -> "Code is valid. Expires at: " + atmCode.getExpiresAt();
            case USED      -> "Code was successfully redeemed.";
            case EXPIRED   -> "Code has expired.";
            case CANCELLED -> "Code was cancelled.";
        };

        return toResponse(atmCode, message);
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void expireOldCodes() {
        int expired = atmCodeRepository.expirePendingCodes(LocalDateTime.now());
        if (expired > 0) {
            log.info("Expired {} stale ATM code(s)", expired);
        }
    }

    private String generateSecure12DigitCode() {
        String code;
        int attempts = 0;
        do {
            if (++attempts > 10) {
                throw new IllegalStateException("Unable to generate a unique ATM code after 10 attempts");
            }
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