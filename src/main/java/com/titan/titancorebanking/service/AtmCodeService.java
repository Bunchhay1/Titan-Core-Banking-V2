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
    private final DoubleEntryService doubleEntryService;

    public AtmCodeService(AtmCodeRepository atmCodeRepository,
                          AccountRepository accountRepository,
                          PasswordEncoder passwordEncoder,
                          TransactionAuditService auditService,
                          EventPublisherService eventPublisherService,
                          DoubleEntryService doubleEntryService) {
        this.atmCodeRepository = atmCodeRepository;
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.eventPublisherService = eventPublisherService;
        this.doubleEntryService = doubleEntryService;
    }

    @Transactional
    public AtmCodeResponse generateCode(AtmGenerateRequest request, String currentUsername) {
        Account account = accountRepository.findByAccountNumber(request.accountNumber())
                .orElseThrow(() -> new RuntimeException("Account not found: " + request.accountNumber()));

        if (!account.getUser().getUsername().equals(currentUsername)) {
            throw new SecurityException("  You do not own this account");
        }

        if (!passwordEncoder.matches(request.pin(), account.getUser().getPin())) {
            throw new SecurityException("  Invalid PIN");
        }

        if (account.getBalance().compareTo(request.amount()) < 0) {
            throw new IllegalStateException("  Insufficient balance for this ATM withdrawal");
        }

        List<AtmCode> pending = atmCodeRepository.findByAccount_IdAndStatus(
                account.getId(), AtmCodeStatus.PENDING);
        if (!pending.isEmpty()) {
            pending.forEach(c -> c.setStatus(AtmCodeStatus.CANCELLED));
            atmCodeRepository.saveAll(pending);
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

        return toResponse(atmCode, "Code generated. Valid for " + codeExpiryMinutes + " minutes.");
    }

    @Transactional
    public AtmCodeResponse redeemCode(AtmRedeemRequest request) {
        // Lock the ATM code row FIRST
        AtmCode atmCode = atmCodeRepository.findByCodeWithLock(request.code())
                .orElseThrow(() -> new IllegalArgumentException("  Invalid ATM code"));

        if (atmCode.getStatus() != AtmCodeStatus.PENDING) {
            throw new IllegalStateException("  This code is " + atmCode.getStatus().name());
        }

        if (LocalDateTime.now().isAfter(atmCode.getExpiresAt())) {
            atmCode.setStatus(AtmCodeStatus.EXPIRED);
            atmCodeRepository.save(atmCode);
            throw new IllegalStateException("  This code has expired");
        }

        // Lock the associated Account row SECOND
        Account account = accountRepository.findByAccountNumberWithLock(atmCode.getAccount().getAccountNumber())
                .orElseThrow(() -> new RuntimeException("Account not found"));

        if (account.getBalance().compareTo(atmCode.getAmount()) < 0) {
            throw new IllegalStateException("  Insufficient funds at time of redemption");
        }

        // Deduct
        account.setBalance(account.getBalance().subtract(atmCode.getAmount()));
        accountRepository.save(account);

        // Finalize Code State
        atmCode.setStatus(AtmCodeStatus.USED);
        atmCode.setRedeemedAt(LocalDateTime.now());
        atmCode.setAtmTerminalId(request.terminalId());
        atmCodeRepository.save(atmCode);

        Transaction tx = auditService.saveAuditLog(
                account, null, atmCode.getAmount(),
                TransactionType.WITHDRAWAL, TransactionStatus.SUCCESS,
                "ATM cardless withdrawal | code=" + atmCode.getCode() + " | terminal=" + request.terminalId());

        // Assuming ID 3 is the ATM Vault GL Account
        doubleEntryService.createDoubleEntry(tx.getId(), account.getId(), 3L, atmCode.getAmount(), tx.getNote(), "SYSTEM_ATM");

        eventPublisherService.publishTransactionCompletedEvent(tx);

        return toResponse(atmCode, "Withdrawal successful. Cash has been dispensed.");
    }

    @Transactional
    public AtmCodeResponse cancelCode(String code, String currentUsername) {
        AtmCode atmCode = atmCodeRepository.findByCode(code)
                .orElseThrow(() -> new RuntimeException("  ATM code not found"));

        if (!atmCode.getAccount().getUser().getUsername().equals(currentUsername)) {
            throw new SecurityException("  You do not own this code");
        }

        if (atmCode.getStatus() != AtmCodeStatus.PENDING) {
            throw new IllegalStateException("Only PENDING codes can be cancelled.");
        }

        atmCode.setStatus(AtmCodeStatus.CANCELLED);
        atmCodeRepository.save(atmCode);

        return toResponse(atmCode, "ATM code cancelled successfully.");
    }

    @Transactional(readOnly = true)
    public AtmCodeResponse getCodeStatus(String code, String currentUsername) {
        AtmCode atmCode = atmCodeRepository.findByCode(code)
                .orElseThrow(() -> new RuntimeException("  ATM code not found"));

        if (!atmCode.getAccount().getUser().getUsername().equals(currentUsername)) {
            throw new SecurityException("  You do not own this code");
        }

        return toResponse(atmCode, "Status: " + atmCode.getStatus());
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void expireOldCodes() {
        atmCodeRepository.expirePendingCodes(LocalDateTime.now());
    }

    private String generateSecure12DigitCode() {
        String code;
        int attempts = 0;
        do {
            if (++attempts > 10) throw new IllegalStateException("System busy");
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