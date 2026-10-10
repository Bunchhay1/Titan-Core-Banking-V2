package com.titan.titancorebanking.service;

import com.titan.titancorebanking.dto.RewardEventDto;
import com.titan.titancorebanking.enums.TransactionStatus;
import com.titan.titancorebanking.enums.TransactionType;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Domain Service for Rewards (Business Layer)
 * Strictly isolated for DB Transaction Management.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RewardDomainService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public record RewardResult(boolean isProcessed, boolean isDuplicate, String eventId, BigDecimal amount, String currency, BigDecimal newBalance) {}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RewardResult processRewardAtomic(RewardEventDto event) {

        // 1. Pessimistic Lock immediately to prevent race conditions (Lock គណនីមុនគេ)
        Account account = accountRepository.findByIdWithLock(event.accountId())
                .orElseThrow(() -> new IllegalStateException("Account not found: " + event.accountId()));

        // 2. Safe Idempotency Check inside lock (ត្រួតពិនិត្យ Idempotency ក្នុង Lock)
        if (transactionRepository.existsByIdempotencyKey(event.eventId())) {
            return new RewardResult(false, true, event.eventId(), BigDecimal.ZERO, event.currency(), account.getBalance());
        }

        try {
            // 3. Mutate State (ផ្លាស់ប្តូរទិន្នន័យអតិថិជន)
            BigDecimal amount = event.rewardAmount();
            account.setBalance(account.getBalance().add(amount));
            accountRepository.save(account);

            // 4. Save Transaction with Unique Constraint (រក្សាទុកប្រវត្តិប្រតិបត្តិការ)
            Transaction tx = Transaction.builder()
                    .transactionType(TransactionType.DEPOSIT)
                    .amount(amount)
                    .toAccount(account)
                    .timestamp(LocalDateTime.now())
                    .status(TransactionStatus.SUCCESS)
                    .note(event.description() != null ? event.description() : "Promotion Reward")
                    .idempotencyKey(event.eventId()) // DB MUST have UNIQUE constraint on this column
                    .transactionReference("RWD-" + event.eventId().substring(0, Math.min(event.eventId().length(), 8)).toUpperCase())
                    .build();

            transactionRepository.save(tx);

            log.info("[REWARD] ✅ Credited {} to accountId={}", amount, event.accountId());
            return new RewardResult(true, false, event.eventId(), amount, event.getCurrencySafe(), account.getBalance());

        } catch (DataIntegrityViolationException e) {
            // Fallback for extreme concurrency if DB Unique Constraint catches a duplicate
            log.warn("[REWARD] Duplicate event caught by DB constraint: {}", event.eventId());
            return new RewardResult(false, true, event.eventId(), BigDecimal.ZERO, event.getCurrencySafe(), account.getBalance());
        }
    }
}