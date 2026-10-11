package com.titan.titancorebanking.service;

import com.titan.titancorebanking.dto.request.AccountRequest;
import com.titan.titancorebanking.dto.request.TransactionRequest;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.model.User;
import com.titan.titancorebanking.enums.AccountType;
import com.titan.titancorebanking.enums.AccountStatus;
import com.titan.titancorebanking.enums.Currency;
import com.titan.titancorebanking.enums.TransactionStatus;
import com.titan.titancorebanking.enums.TransactionType;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.TransactionRepository;
import com.titan.titancorebanking.repository.UserRepository;
import com.titan.titancorebanking.utils.AccountNumberUtils;
import com.titan.titancorebanking.service.imple.OtpService;
import com.titan.titancorebanking.service.imple.ExchangeRateService;
import com.titan.titancorebanking.exception.InsufficientBalanceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.github.resilience4j.retry.annotation.Retry;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final AccountBucketService accountBucketService;

    @Autowired
    @Lazy
    private QrPaymentService qrPaymentService;

    private static final int MAX_ACCOUNTS = 10;
    private static final int ACCOUNT_GENERATION_MAX_RETRIES = 5;

    @Transactional
    @CacheEvict(value = "user_accounts", key = "#username")
    @Retry(name = "db")
    public Account createAccount(AccountRequest request, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        if (accountRepository.countByUser(user) >= MAX_ACCOUNTS) {
            throw new IllegalStateException("Limit Reached: You can only create " + MAX_ACCOUNTS + " accounts.");
        }

        // Updated for Java 21 Record accessors
        AccountType type = parseEnum(AccountType.class, request.accountType(), AccountType.SAVINGS);
        Currency currency = parseEnum(Currency.class, request.currency(), Currency.USD);

        String newAccountNumber = generateUniqueAccountNumber();

        Account account = Account.builder()
                .accountNumber(newAccountNumber)
                .accountType(type)
                .balance(request.initialDeposit() != null ? request.initialDeposit() : BigDecimal.ZERO)
                .user(user)
                .createdAt(LocalDateTime.now())
                .status(AccountStatus.ACTIVE)
                .currency(currency)
                .overdraftLimit(BigDecimal.ZERO)
                .build();

        account = accountRepository.save(account);

        try {
            accountBucketService.getOrInitializeBuckets(account);
        } catch (Exception e) {
            log.warn("Could not pre-init buckets for account {}: {}", account.getAccountNumber(), e.getMessage());
        }

        try {
            qrPaymentService.getOrCreateAccountQr(account.getAccountNumber(), username);
        } catch (Exception e) {
            log.warn("Could not auto-create static QR for {}: {}", account.getAccountNumber(), e.getMessage());
        }

        return account;
    }

    @Transactional(readOnly = true)
    public List<Account> getMyAccounts(String username) {
        List<Account> accounts = accountRepository.findByUserUsername(username);
        for (Account account : accounts) {
            BigDecimal aggregated = accountBucketService.calculateTotalAggregatedBalance(account.getId(), account.getBalance());
            account.setBalance(aggregated);
        }
        return accounts;
    }

    @Transactional(readOnly = true)
    public Optional<Account> getAccountById(Long id) {
        return accountRepository.findById(id).map(account -> {
            BigDecimal aggregated = accountBucketService.calculateTotalAggregatedBalance(account.getId(), account.getBalance());
            account.setBalance(aggregated);
            return account;
        });
    }

    @Transactional(readOnly = true)
    public BigDecimal getBalance(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .map(account -> accountBucketService.calculateTotalAggregatedBalance(account.getId(), account.getBalance()))
                .orElse(BigDecimal.ZERO);
    }

    private String generateUniqueAccountNumber() {
        String newAccountNumber;
        int attempts = 0;
        do {
            newAccountNumber = AccountNumberUtils.generateAccountNumber();
            attempts++;
            if (attempts > ACCOUNT_GENERATION_MAX_RETRIES) {
                throw new IllegalStateException("System Busy: Could not generate a unique account number after multiple attempts.");
            }
        } while (accountRepository.existsByAccountNumber(newAccountNumber));
        return newAccountNumber;
    }

    private <T extends Enum<T>> T parseEnum(Class<T> enumType, String value, T defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Enum.valueOf(enumType, value.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid enum value for {}: {}. Defaulting to {}.", enumType.getSimpleName(), value, defaultValue);
            return defaultValue;
        }
    }
}