package com.titan.titancorebanking.service;

import com.titan.titancorebanking.dto.request.AccountRequest;
import com.titan.titancorebanking.model.Account;
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
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {

    private static final int MAX_ACCOUNTS = 10;

    // [MODIFIED] Removed 5 redundant dependencies (TransactionRepository, PasswordEncoder, OtpService, etc.)
    // ផ្តាច់ Dependencies ណាដែលមិនពាក់ព័ន្ធចេញពី AccountService ដើម្បីកាត់បន្ថយការផ្ទុកមេម៉ូរី (Memory consumption)។
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final AccountBucketService accountBucketService;

    @Autowired
    @Lazy
    private QrPaymentService qrPaymentService;

    @Transactional
    @CacheEvict(value = "user_accounts", key = "#username")
    @Retry(name = "db")
    public Account createAccount(AccountRequest request, String username) {
        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        if (accountRepository.countByUser(user) >= MAX_ACCOUNTS) {
            throw new IllegalStateException("Limit Reached: You can only create " + MAX_ACCOUNTS + " accounts.");
        }

        // [MODIFIED] Extracted Enum parsing into a generic helper method.
        var type = parseEnum(AccountType.class, request.getAccountType(), AccountType.SAVINGS);
        var currency = parseEnum(Currency.class, request.getCurrency(), Currency.USD);
        var newAccountNumber = generateUniqueAccountNumber();
        var initialDeposit = request.getInitialDeposit() != null ? request.getInitialDeposit() : BigDecimal.ZERO;

        var account = Account.builder()
                .accountNumber(newAccountNumber)
                .accountType(type)
                .balance(initialDeposit)
                .user(user)
                .createdAt(LocalDateTime.now())
                .status(AccountStatus.ACTIVE)
                .currency(currency)
                .overdraftLimit(BigDecimal.ZERO)
                .build();

        account = accountRepository.save(account);

        initializeBucketsSafe(account);
        generateAccountQrSafe(account.getAccountNumber(), username);

        return account;
    }

    @Transactional(readOnly = true)
    public List<Account> getMyAccounts(String username) {
        var accounts = accountRepository.findByUserUsername(username);
        // [MODIFIED] Replaced imperative loop with declarative method reference.
        accounts.forEach(this::aggregateBalance);
        return accounts;
    }

    @Transactional(readOnly = true)
    public Optional<Account> getAccountById(Long id) {
        return accountRepository.findById(id).map(this::aggregateBalance);
    }

    @Transactional(readOnly = true)
    public BigDecimal getBalance(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .map(acc -> accountBucketService.calculateTotalAggregatedBalance(acc.getId(), acc.getBalance()))
                .orElse(BigDecimal.ZERO);
    }

    // =========================================================================
    // PRIVATE HELPERS (Clean Code Architecture)
    // =========================================================================

    private <T extends Enum<T>> T parseEnum(Class<T> enumType, String value, T defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Enum.valueOf(enumType, value.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid {}: {}. Defaulting to {}", enumType.getSimpleName(), value, defaultValue);
            return defaultValue;
        }
    }

    private String generateUniqueAccountNumber() {
        int attempts = 0;
        String newAccountNumber;
        do {
            if (++attempts > 5) {
                throw new IllegalStateException("System Busy: Could not generate unique account number.");
            }
            newAccountNumber = AccountNumberUtils.generateAccountNumber();
        } while (accountRepository.existsByAccountNumber(newAccountNumber));
        return newAccountNumber;
    }

    private void initializeBucketsSafe(Account account) {
        try {
            accountBucketService.getOrInitializeBuckets(account);
        } catch (Exception e) {
            log.debug("Could not pre-init buckets for account {}: {}", account.getAccountNumber(), e.getMessage());
        }
    }

    private void generateAccountQrSafe(String accountNumber, String username) {
        try {
            qrPaymentService.getOrCreateAccountQr(accountNumber, username);
            log.debug("Account QR auto-created for new account: {}", accountNumber);
        } catch (Exception e) {
            log.warn("Could not auto-create account QR for {}: {}", accountNumber, e.getMessage());
        }
    }

    private Account aggregateBalance(Account account) {
        var aggregated = accountBucketService.calculateTotalAggregatedBalance(account.getId(), account.getBalance());
        account.setBalance(aggregated);
        return account;
    }
}