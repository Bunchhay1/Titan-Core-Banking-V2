package com.titan.titancorebanking.service;

import com.titan.titancorebanking.dto.request.AtmGenerateRequest;
import com.titan.titancorebanking.dto.request.AtmRedeemRequest;
import com.titan.titancorebanking.dto.response.AtmCodeResponse;
import com.titan.titancorebanking.exception.InsufficientBalanceException;
import com.titan.titancorebanking.exception.InvalidPinException;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.AtmCode;
import com.titan.titancorebanking.model.AtmCode.AtmCodeStatus;
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
        this.atmCodeRepository     = atmCodeRepository;
        this.accountRepository     = accountRepository;
        this.passwordEncoder       = passwordEncoder;
        this.auditService          = auditService;
        this.eventPublisherService = eventPublisherService;
    }

    @Transactional
    public AtmCodeResponse generateCode(AtmGenerateRequest request, String currentUsername) {

        // [MODIFIED] Extracted account fetching and ownership verification into a helper method.
        // បំបែកការទាញយកគណនី និងការត្រួតពិនិត្យសិទ្ធិម្ចាស់គណនី ទៅជា Method មួយដាច់ដោយឡែក ដើម្បីប្រើប្រាស់ឡើងវិញ។
        var account = getAccountAndValidateOwnership(request.accountNumber(), currentUsername);

        // [MODIFIED] Replaced generic RuntimeException with specific domain exceptions.
        // ប្រើ Domain Exception (InvalidPinException, InsufficientBalanceException) ជំនួសឲ្យ RuntimeException ទូទៅ។
        validatePin(account, request.pin());

        if (account.getBalance().compareTo(request.amount()) < 0) {
            throw new InsufficientBalanceException("Insufficient balance for this ATM withdrawal");
        }

        // [MODIFIED] Extracted cancellation of old codes into a helper method.
        // ញែកកូដដែលធ្វើការលុបចោល (Cancel) កូដចាស់ៗដែលនៅ Pending ចេញពី Main Flow។
        cancelExistingPendingCodes(account.getId());

        var atmCode = AtmCode.builder()
                .code(generateSecure12DigitCode())
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
        // [MODIFIED] Used 'var' for local variables to reduce verbosity.
        var atmCode = atmCodeRepository.findByCodeWithLock(request.code())
                .orElseThrow(() -> new IllegalArgumentException("Invalid ATM code"));

        // [MODIFIED] Encapsulated all status and expiry validations.
        // ប្រមូលផ្តុំការត្រួតពិនិត្យស្ថានភាពកូដ (Used, Expired, Cancelled) និងការពិនិត្យពេលវេលាផុតកំណត់ ចូលក្នុង Helper Method តែមួយ។
        validateCodeForRedemption(atmCode);

        var account = accountRepository.findByAccountNumberWithLock(atmCode.getAccount().getAccountNumber())
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));

        if (account.getBalance().compareTo(atmCode.getAmount()) < 0) {
            throw new InsufficientBalanceException("Insufficient funds at time of redemption");
        }

        account.setBalance(account.getBalance().subtract(atmCode.getAmount()));
        accountRepository.save(account);

        atmCode.setStatus(AtmCodeStatus.USED);
        atmCode.setRedeemedAt(LocalDateTime.now());
        atmCode.setAtmTerminalId(request.terminalId());
        atmCodeRepository.save(atmCode);

        var tx = auditService.saveAuditLog(
                account, null, atmCode.getAmount(),
                TransactionType.WITHDRAWAL, TransactionStatus.SUCCESS,
                "ATM cardless withdrawal | code=" + atmCode.getCode() + (request.terminalId() != null ? " | terminal=" + request.terminalId() : ""));

        eventPublisherService.publishTransactionCompletedEvent(tx);

        log.info("ATM withdrawal OK | account={} amount={} terminal={}", account.getAccountNumber(), atmCode.getAmount(), request.terminalId());
        return toResponse(atmCode, "Withdrawal successful. Cash has been dispensed.");
    }

    @Transactional
    public AtmCodeResponse cancelCode(String code, String currentUsername) {
        var atmCode = getCodeAndValidateOwnership(code, currentUsername);

        if (atmCode.getStatus() != AtmCodeStatus.PENDING) {
            throw new IllegalStateException("Only PENDING codes can be cancelled (current: " + atmCode.getStatus() + ")");
        }

        atmCode.setStatus(AtmCodeStatus.CANCELLED);
        atmCodeRepository.save(atmCode);
        log.info("ATM code cancelled by user={}", currentUsername);

        return toResponse(atmCode, "ATM code cancelled successfully.");
    }

    @Transactional(readOnly = true)
    public AtmCodeResponse getCodeStatus(String code, String currentUsername) {
        var atmCode = getCodeAndValidateOwnership(code, currentUsername);

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

    // =========================================================================
    // Private Helpers (Clean Code Architecture)
    // =========================================================================

    private Account getAccountAndValidateOwnership(String accountNumber, String username) {
        var account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountNumber));
        if (!account.getUser().getUsername().equals(username)) {
            throw new SecurityException("You do not own this account");
        }
        return account;
    }

    private AtmCode getCodeAndValidateOwnership(String code, String username) {
        var atmCode = atmCodeRepository.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("ATM code not found"));
        if (!atmCode.getAccount().getUser().getUsername().equals(username)) {
            throw new SecurityException("You do not own this code");
        }
        return atmCode;
    }

    private void validatePin(Account account, String pin) {
        if (!passwordEncoder.matches(pin, account.getUser().getPin())) {
            throw new InvalidPinException("Invalid PIN");
        }
    }

    private void cancelExistingPendingCodes(Long accountId) {
        var pending = atmCodeRepository.findByAccount_IdAndStatus(accountId, AtmCodeStatus.PENDING);
        if (!pending.isEmpty()) {
            pending.forEach(c -> c.setStatus(AtmCodeStatus.CANCELLED));
            atmCodeRepository.saveAll(pending);
            log.info("Cancelled {} existing pending ATM code(s) for account ID {}", pending.size(), accountId);
        }
    }

    private void validateCodeForRedemption(AtmCode atmCode) {
        switch (atmCode.getStatus()) {
            case USED      -> throw new IllegalStateException("This code has already been used");
            case EXPIRED   -> throw new IllegalStateException("This code has expired");
            case CANCELLED -> throw new IllegalStateException("This code was cancelled");
            case PENDING   -> {}
        }

        if (LocalDateTime.now().isAfter(atmCode.getExpiresAt())) {
            atmCode.setStatus(AtmCodeStatus.EXPIRED);
            atmCodeRepository.save(atmCode);
            throw new IllegalStateException("This code has expired");
        }
    }

    private String generateSecure12DigitCode() {
        int attempts = 0;
        String code;
        do {
            if (++attempts > 10) {
                throw new IllegalStateException("Unable to generate a unique ATM code after 10 attempts");
            }
            int first = 1 + SECURE_RANDOM.nextInt(9);
            var sb = new StringBuilder(12).append(first);
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