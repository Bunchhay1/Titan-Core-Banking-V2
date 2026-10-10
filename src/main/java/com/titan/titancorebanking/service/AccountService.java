package com.titan.titancorebanking.service;

import com.titan.titancorebanking.dto.request.AccountRequest;
import com.titan.titancorebanking.dto.response.AccountResponse;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.User;
import com.titan.titancorebanking.exception.AccountLimitExceededException;
import com.titan.titancorebanking.exception.ResourceNotFoundException;
import com.titan.titancorebanking.event.AccountCreatedEvent;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.dao.DataAccessException;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final AccountBucketService accountBucketService;
    private final AccountGeneratorService accountGeneratorService;
    private final ApplicationEventPublisher eventPublisher;

    private static final int MAX_ACCOUNTS = 10;

    @Transactional
    @CacheEvict(value = "user_accounts", key = "#username")
    @Retry(name = "db")
    public AccountResponse createAccount(AccountRequest request, String username) {
        // Defensive Programming: Fail Fast
        Assert.hasText(username, "Username must not be blank");
        Assert.notNull(request, "AccountRequest must not be null");

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        if (accountRepository.countByUser(user) >= MAX_ACCOUNTS) {
            log.warn("Account creation blocked. User {} reached max limit.", username);
            throw new AccountLimitExceededException("Limit Reached: Maximum of " + MAX_ACCOUNTS + " accounts allowed.");
        }

        // Generate and persist account in a separate transaction boundary if needed
        Account account = accountGeneratorService.generateAndPersistAccount(
                user, request.getAccountType(), request.getCurrency()
        );

        try {
            accountBucketService.getOrInitializeBuckets(account);
        } catch (DataAccessException | IllegalStateException e) {
            // Eliminated generic Exception catching. Log specifically.
            log.error("Non-fatal: Failed to pre-init buckets for account {}. Reason: {}", account.getAccountNumber(), e.getMessage());
        }

        // Publish event for downstream async processing (e.g., Kafka, Notifications)
        eventPublisher.publishEvent(new AccountCreatedEvent(this, account.getAccountNumber(), username));

        return mapToAccountResponse(account);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> getMyAccounts(String username) {
        Assert.hasText(username, "Username must not be blank");

        List<Account> accounts = accountRepository.findByUserUsername(username);

        // Maps entities to immutable records/DTOs to avoid @Transient domain leaks
        return accounts.stream()
                .map(this::mapToAccountResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccountById(Long id, String username) {
        Assert.notNull(id, "Account ID must not be null");
        Assert.hasText(username, "Username must not be blank");

        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account ID " + id + " not found"));

        verifyAccountOwnership(account, username);

        return mapToAccountResponse(account);
    }

    @Transactional(readOnly = true)
    public BigDecimal getBalance(String accountNumber, String username) {
        Assert.hasText(accountNumber, "Account number must not be blank");
        Assert.hasText(username, "Username must not be blank");

        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Account Number " + accountNumber + " not found"));

        verifyAccountOwnership(account, username);

        return accountBucketService.calculateTotalAggregatedBalance(account.getId(), account.getBalance());
    }

    /**
     * Centralized IDOR (Insecure Direct Object Reference) Prevention.
     * ការពារមិនឲ្យអ្នកប្រើប្រាស់ចូលមើលទិន្នន័យគណនីរបស់អ្នកដទៃបាន។
     */
    private void verifyAccountOwnership(Account account, String username) {
        if (!account.getUser().getUsername().equals(username)) {
            log.warn("Security Alert: User {} attempted unauthorized access on Account ID {}", username, account.getId());
            throw new AccessDeniedException("Unauthorized: You do not have permission to access this account.");
        }
    }

    /**
     * Factory method for mapping Domain Entity to Presentation DTO.
     * ជៀសវាងការប្រើ @Transient ក្នុង Entity ដើម្បីរក្សាស្ថាបត្យកម្មឲ្យស្អាត។
     */
    private AccountResponse mapToAccountResponse(Account account) {
        BigDecimal aggregated = accountBucketService.calculateTotalAggregatedBalance(account.getId(), account.getBalance());

        // Requires creating AccountResponse record containing these fields
        return new AccountResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getAccountType(),
                account.getCurrency(),
                account.getBalance(),
                aggregated,
                account.getStatus()
        );
    }
}