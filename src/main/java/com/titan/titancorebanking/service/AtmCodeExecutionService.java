package com.titan.titancorebanking.service;

import com.titan.titancorebanking.dto.request.AtmGenerateRequest;
import com.titan.titancorebanking.dto.request.AtmRedeemRequest;
import com.titan.titancorebanking.enums.TransactionStatus;
import com.titan.titancorebanking.enums.TransactionType;
import com.titan.titancorebanking.exception.AtmCodeException;
import com.titan.titancorebanking.exception.InsufficientBalanceException;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.AtmCode;
import com.titan.titancorebanking.model.AtmCode.AtmCodeStatus;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.AtmCodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Handles strict ACID state transitions for ATM operations.
 * Enforces lock ordering, mitigates check-then-act vulnerabilities, and optimizes bulk transitions.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AtmCodeExecutionService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int MAX_GENERATION_ATTEMPTS = 5;

    @Value("${atm.code.expiry-minutes:10}")
    private int codeExpiryMinutes;

    private final AtmCodeRepository atmCodeRepository;
    private final AccountRepository accountRepository;
    private final TransactionAuditService auditService;
    private final EventPublisherService eventPublisherService;
    private final Clock clock; // Deterministic time for testing and precise lifecycle management

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public AtmCode executeGenerateCodeAtomically(AtmGenerateRequest request, Long accountId) {

        Account account = accountRepository.findByIdWithLock(accountId)
                .orElseThrow(() -> new IllegalStateException("Account lock acquisition failed"));

        if (account.getBalance().compareTo(request.amount()) < 0) {
            throw new InsufficientBalanceException("Insufficient balance for this ATM withdrawal");
        }

        // Optimized state transition via single DB command (Eliminates N+1 issue)
        atmCodeRepository.cancelPendingCodesByAccountId(account.getId());

        AtmCode atmCode = buildAndSaveUniqueAtmCode(account, request.amount());
        return atmCode;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public AtmCode executeRedeemCodeAtomically(AtmRedeemRequest request) {

        AtmCode atmCode = atmCodeRepository.findByCodeWithLock(request.code())
                .orElseThrow(() -> new AtmCodeException("Invalid ATM code provided"));

        if (atmCode.getStatus() != AtmCodeStatus.PENDING) {
            throw new AtmCodeException(String.format("Invalid state transition. Current status: %s", atmCode.getStatus()));
        }

        if (LocalDateTime.now(clock).isAfter(atmCode.getExpiresAt())) {
            atmCode.setStatus(AtmCodeStatus.EXPIRED);
            atmCodeRepository.save(atmCode);
            throw new AtmCodeException("ATM code has expired");
        }

        Account account = accountRepository.findByIdWithLock(atmCode.getAccount().getId())
                .orElseThrow(() -> new IllegalStateException("Account integrity validation failed"));

        if (account.getBalance().compareTo(atmCode.getAmount()) < 0) {
            throw new InsufficientBalanceException("Insufficient funds at time of redemption");
        }

        account.setBalance(account.getBalance().subtract(atmCode.getAmount()));
        accountRepository.save(account);

        atmCode.setStatus(AtmCodeStatus.USED);
        atmCode.setRedeemedAt(LocalDateTime.now(clock));
        atmCode.setAtmTerminalId(request.terminalId());
        atmCodeRepository.save(atmCode);

        Transaction tx = auditService.saveAuditLog(
                account, null, atmCode.getAmount(),
                TransactionType.WITHDRAWAL, TransactionStatus.SUCCESS,
                String.format("ATM withdrawal | code=%s | term=%s", atmCode.getCode(), request.terminalId()));

        eventPublisherService.publishTransactionCompletedEvent(tx);

        return atmCode;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AtmCode executeCancelCodeAtomically(String code, String currentUsername) {
        AtmCode atmCode = atmCodeRepository.findByCodeWithLock(code)
                .orElseThrow(() -> new AtmCodeException("ATM code not found"));

        if (!atmCode.getAccount().getUser().getUsername().equals(currentUsername)) {
            throw new SecurityException("Unauthorized ATM code access attempt");
        }

        if (atmCode.getStatus() != AtmCodeStatus.PENDING) {
            throw new AtmCodeException("Only PENDING codes are eligible for cancellation");
        }

        atmCode.setStatus(AtmCodeStatus.CANCELLED);
        return atmCodeRepository.save(atmCode);
    }

    @Scheduled(fixedDelayString = "${atm.code.cleanup-delay-ms:60000}")
    @Transactional
    public void expireOldCodes() {
        // Leverages DB-level bulk update
        atmCodeRepository.expirePendingCodes(LocalDateTime.now(clock));
    }

    /**
     * Resolves Check-Then-Act Race Condition by explicitly relying on Database Unique Constraints.
     * បញ្ចៀសបញ្ហាប្រជែងគ្នាដោយពឹងផ្អែកលើ Database Constraints ជំនួសឲ្យការអានទិន្នន័យមកពិនិត្យមុន។
     */
    private AtmCode buildAndSaveUniqueAtmCode(Account account, java.math.BigDecimal amount) {
        int attempts = 0;
        while (attempts < MAX_GENERATION_ATTEMPTS) {
            try {
                AtmCode atmCode = AtmCode.builder()
                        .code(generateSecure12DigitCode())
                        .account(account)
                        .amount(amount)
                        .status(AtmCodeStatus.PENDING)
                        .expiresAt(LocalDateTime.now(clock).plusMinutes(codeExpiryMinutes))
                        .build();
                return atmCodeRepository.save(atmCode); // DB throws DataIntegrityViolationException on duplicate
            } catch (DataIntegrityViolationException ex) {
                attempts++;
                log.warn("ATM code collision detected. Retrying attempt {}/{}", attempts, MAX_GENERATION_ATTEMPTS);
            }
        }
        log.error("Exhausted ATM code generation attempts for Account ID: {}", account.getId());
        throw new IllegalStateException("Failed to generate a highly-entropic ATM code safely");
    }

    private String generateSecure12DigitCode() {
        int first = 1 + SECURE_RANDOM.nextInt(9);
        StringBuilder sb = new StringBuilder(12).append(first);
        for (int i = 1; i < 12; i++) {
            sb.append(SECURE_RANDOM.nextInt(10));
        }
        return sb.toString();
    }
}