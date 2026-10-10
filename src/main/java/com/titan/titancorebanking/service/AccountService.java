package com.titan.titancorebanking.service;

import com.titan.titancorebanking.dto.request.AccountRequest;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.User;
import com.titan.titancorebanking.enums.AccountType;
import com.titan.titancorebanking.enums.AccountStatus;
import com.titan.titancorebanking.enums.Currency;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.UserRepository;
import com.titan.titancorebanking.utils.AccountNumberUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.github.resilience4j.retry.annotation.Retry;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Strictly handles Account Lifecycle Management.
 * SRP Enforced: Transaction and Transfer logic has been extracted to TransactionOrchestratorService.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final AccountBucketService accountBucketService;

    // @Lazy breaks the circular dependency:
    // AccountService → QrPaymentService → AccountRepository (← same bean AccountService uses)
    @Autowired
    @Lazy
    private QrPaymentService qrPaymentService;

    private static final int MAX_ACCOUNTS = 10;

    @Transactional
    @CacheEvict(value = "user_accounts", key = "#username")
    @Retry(name = "db")
    public Account createAccount(AccountRequest request, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        if (accountRepository.countByUser(user) >= MAX_ACCOUNTS) {
            throw new RuntimeException("⛔ Limit Reached: You can only create " + MAX_ACCOUNTS + " accounts.");
        }

        AccountType type = AccountType.SAVINGS;
        if (request.getAccountType() != null) {
            try {
                type = AccountType.valueOf(request.getAccountType().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid AccountType: {}. Defaulting to SAVINGS.", request.getAccountType());
            }
        }

        Currency currency = Currency.USD;
        if (request.getCurrency() != null) {
            try {
                currency = Currency.valueOf(request.getCurrency().toUpperCase());
            } catch (Exception e) {
                log.warn("Invalid Currency: {}. Defaulting to USD.", request.getCurrency());
            }
        }

        String newAccountNumber;
        int attempts = 0;
        do {
            newAccountNumber = AccountNumberUtils.generateAccountNumber();
            attempts++;
            if (attempts > 5) throw new RuntimeException("🔥 System Busy: Could not generate unique account number.");
        } while (accountRepository.existsByAccountNumber(newAccountNumber));

        Account account = Account.builder()
                .accountNumber(newAccountNumber)
                .accountType(type)
                .balance(request.getInitialDeposit() != null ? request.getInitialDeposit() : BigDecimal.ZERO)
                .user(user)
                .createdAt(LocalDateTime.now())
                .status(AccountStatus.ACTIVE)
                .currency(currency)
                .overdraftLimit(BigDecimal.ZERO)
                .build();

        account = accountRepository.save(account);

        // Pre-initialize partitioned balance buckets for the account
        try {
            accountBucketService.getOrInitializeBuckets(account);
        } catch (Exception e) {
            log.debug("Could not pre-init buckets: {}", e.getMessage());
        }

        // Auto-create a permanent account QR so the iOS app can show it immediately.
        try {
            qrPaymentService.getOrCreateAccountQr(account.getAccountNumber(), username);
            log.debug("Account QR auto-created for new account: {}", account.getAccountNumber());
        } catch (Exception e) {
            log.warn("⚠️ Could not auto-create account QR for {}: {}", account.getAccountNumber(), e.getMessage());
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
    public java.util.Optional<Account> getAccountById(Long id) {
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
}