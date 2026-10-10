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
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.github.resilience4j.retry.annotation.Retry;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * JAVA 21 MODERNIZED: Enterprise Account Service
 * - Enforces Single Responsibility (Account Lifecycle ONLY)
 * - Eliminates duplicate transfer/transaction logic
 * - Hardens generation loops against infinite collisions
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {

    private static final int MAX_ACCOUNTS_PER_USER = 10;
    private static final int MAX_ACCOUNT_GENERATION_ATTEMPTS = 5;

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final AccountBucketService accountBucketService;

    // @Lazy breaks the circular dependency: AccountService <-> QrPaymentService
    @Autowired
    @Lazy
    private QrPaymentService qrPaymentService;

    // REFACTOR: Deleted transferMoney(), handlePinFailure(), and resetPinAttempts().
    // Money movement and PIN security are now strictly the domain of TransactionService[cite: 1].

    // =========================================================================
    // CREATE ACCOUNT
    // =========================================================================
    @Transactional
    @CacheEvict(value = "user_accounts", key = "#username")
    @Retry(name = "db")
    public Account createAccount(AccountRequest request, String username) {
        // REFACTOR: Use EntityNotFoundException over generic RuntimeException[cite: 1]
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new EntityNotFoundException("User identity not found: " + username));

        if (accountRepository.countByUser(user) >= MAX_ACCOUNTS_PER_USER) {
            throw new IllegalStateException(String.format("Limit Reached: Maximum of %d accounts permitted.", MAX_ACCOUNTS_PER_USER));
        }

        AccountType type = parseAccountType(request.getAccountType());
        Currency currency = parseCurrency(request.getCurrency());
        String newAccountNumber = generateUniqueAccountNumber();

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

        // REFACTOR: Explicitly separated auxiliary initializations to prevent primary transaction failure
        initializePartitionBuckets(account);
        initializeAccountQrCode(account, username);

        return account;
    }

    // =========================================================================
    // READ OPERATIONS
    // =========================================================================

    @Transactional(readOnly = true)
    public List<Account> getMyAccounts(String username) {
        List<Account> accounts = accountRepository.findByUserUsername(username);
        // REFACTOR: Enhanced for-loop converted to standard stream mapping (if preferable) or kept for readability[cite: 1]
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

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    private AccountType parseAccountType(String typeInput) {
        if (typeInput == null || typeInput.isBlank()) {
            return AccountType.SAVINGS;
        }
        try {
            return AccountType.valueOf(typeInput.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid AccountType requested: {}. Defaulting to SAVINGS.", typeInput);
            return AccountType.SAVINGS;
        }
    }

    private Currency parseCurrency(String currencyInput) {
        if (currencyInput == null || currencyInput.isBlank()) {
            return Currency.USD;
        }
        try {
            return Currency.valueOf(currencyInput.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid Currency requested: {}. Defaulting to USD.", currencyInput);
            return Currency.USD;
        }
    }

    // REFACTOR: Extracted and hardened account number generation to prevent infinite loops during extreme concurrency
    private String generateUniqueAccountNumber() {
        int attempts = 0;
        String newAccountNumber;
        do {
            newAccountNumber = AccountNumberUtils.generateAccountNumber();
            attempts++;
            if (attempts > MAX_ACCOUNT_GENERATION_ATTEMPTS) {
                log.error("Exhausted account number generation attempts. Hash collision threshold reached.");
                throw new IllegalStateException("System temporarily unable to generate a unique account number. Please retry.");
            }
        } while (accountRepository.existsByAccountNumber(newAccountNumber));

        return newAccountNumber;
    }

    private void initializePartitionBuckets(Account account) {
        try {
            accountBucketService.getOrInitializeBuckets(account);
        } catch (Exception e) {
            log.warn("Non-fatal: Could not pre-initialize partition buckets for account {}: {}", account.getAccountNumber(), e.getMessage());
        }
    }

    private void initializeAccountQrCode(Account account, String username) {
        try {
            qrPaymentService.getOrCreateAccountQr(account.getAccountNumber(), username);
            log.debug("Permanent Account QR auto-created for new account: {}", account.getAccountNumber());
        } catch (Exception e) {
            log.warn("Non-fatal: Could not auto-create account QR for {}: {}", account.getAccountNumber(), e.getMessage());
        }
    }
}