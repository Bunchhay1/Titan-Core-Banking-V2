package com.titan.titancorebanking.service;

import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.AccountBucket;
import com.titan.titancorebanking.repository.AccountBucketRepository;
import com.titan.titancorebanking.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountBucketService {

    public static final int NUM_BUCKETS = 8;
    private final AccountBucketRepository accountBucketRepository;
    private final AccountRepository accountRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<AccountBucket> getOrInitializeBuckets(Account parentAccount) {
        List<AccountBucket> existing = accountBucketRepository.findByParentAccountId(parentAccount.getId());
        if (existing.size() >= NUM_BUCKETS) {
            return existing;
        }

        List<AccountBucket> buckets = new ArrayList<>(existing);
        for (int i = existing.size(); i < NUM_BUCKETS; i++) {
            try {
                AccountBucket bucket = AccountBucket.builder()
                        .parentAccount(parentAccount)
                        .bucketIndex(i)
                        .balance(BigDecimal.ZERO)
                        .currency(parentAccount.getCurrency())
                        .updatedAt(LocalDateTime.now())
                        .build();
                buckets.add(accountBucketRepository.save(bucket));
            } catch (Exception e) {
                // Ignore unique constraint violations if created concurrently by another thread
                log.trace("Bucket {} already initialized for account {}", i, parentAccount.getId());
            }
        }
        return accountBucketRepository.findByParentAccountId(parentAccount.getId());
    }

    public int selectRandomBucketIndex() {
        return ThreadLocalRandom.current().nextInt(NUM_BUCKETS);
    }

    @Transactional
    public AccountBucket creditBucket(Long parentAccountId, int bucketIndex, BigDecimal amount) {
        AccountBucket bucket = accountBucketRepository
                .findByParentAccountIdAndBucketIndexWithLock(parentAccountId, bucketIndex)
                .orElseGet(() -> {
                    Account parent = accountRepository.findById(parentAccountId)
                            .orElseThrow(() -> new IllegalArgumentException("Account not found: " + parentAccountId));
                    getOrInitializeBuckets(parent);
                    return accountBucketRepository.findByParentAccountIdAndBucketIndexWithLock(parentAccountId, bucketIndex)
                            .orElseThrow(() -> new IllegalStateException("Failed to acquire bucket lock"));
                });

        bucket.setBalance(bucket.getBalance().add(amount));
        bucket.setUpdatedAt(LocalDateTime.now());
        return accountBucketRepository.save(bucket);
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateTotalAggregatedBalance(Long parentAccountId, BigDecimal parentBalance) {
        BigDecimal bucketSum = accountBucketRepository.sumBucketBalancesByParentAccountId(parentAccountId);
        return (parentBalance != null ? parentBalance : BigDecimal.ZERO)
                .add(bucketSum != null ? bucketSum : BigDecimal.ZERO);
    }

    @Scheduled(fixedDelay = 60000)
    public void sweepAllBucketBalances() {
        // Optimization: Only fetch parent accounts that actively have rows in account_buckets
        List<Long> activeParentIds = accountBucketRepository.findDistinctParentAccountIds();
        for (Long accountId : activeParentIds) {
            try {
                sweepAccountBuckets(accountId);
            } catch (Exception e) {
                log.error("Sweep failed for account ID {}: {}", accountId, e.getMessage());
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sweepAccountBuckets(Long parentAccountId) {
        List<AccountBucket> buckets = accountBucketRepository.findByParentAccountId(parentAccountId);
        if (buckets == null || buckets.isEmpty()) {
            return;
        }

        BigDecimal totalToSweep = BigDecimal.ZERO;
        for (AccountBucket b : buckets) {
            if (b.getBalance().compareTo(BigDecimal.ZERO) > 0) {
                totalToSweep = totalToSweep.add(b.getBalance());
            }
        }

        if (totalToSweep.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        // Deterministic locking: Lock parent first, then buckets in order
        Account parent = accountRepository.findByIdWithLock(parentAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Parent account not found: " + parentAccountId));

        for (AccountBucket b : buckets) {
            AccountBucket lockedBucket = accountBucketRepository.findByIdWithLock(b.getId()).orElse(b);
            if (lockedBucket.getBalance().compareTo(BigDecimal.ZERO) > 0) {
                lockedBucket.setBalance(BigDecimal.ZERO);
                lockedBucket.setUpdatedAt(LocalDateTime.now());
                accountBucketRepository.save(lockedBucket);
            }
        }

        parent.setBalance(parent.getBalance().add(totalToSweep));
        accountRepository.save(parent);
    }
}