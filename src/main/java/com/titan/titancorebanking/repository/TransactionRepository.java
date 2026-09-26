package com.titan.titancorebanking.repository;

import com.titan.titancorebanking.model.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    // ✅ TITAN STANDARD: Find by idempotency key for duplicate detection
    java.util.Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    // ✅ FIX: Custom JPQL Query
    // រកមើល Transaction ណាដែល User ជាម្ចាស់គណនីផ្ញើ (fromAccount) ឬ ជាម្ចាស់គណនីទទួល (toAccount)
    @Query("SELECT t FROM Transaction t WHERE " +
            "t.fromAccount.user.username = :username OR " +
            "t.toAccount.user.username = :username " +
            "ORDER BY t.timestamp DESC")
    List<Transaction> findAllByUser(@Param("username") String username);

    // ✅ Find by Account Number (Both Sender & Receiver)
    @Query("SELECT t FROM Transaction t WHERE " +
            "t.fromAccount.accountNumber = :accountNumber OR " +
            "t.toAccount.accountNumber = :accountNumber " +
            "ORDER BY t.timestamp DESC")
    Page<Transaction> findAllByAccountNumber(@Param("accountNumber") String accountNumber, Pageable pageable);
    
    // ✅ Find by multiple accounts (for transaction history)
    @Query("SELECT t FROM Transaction t WHERE " +
            "t.fromAccount IN :fromAccounts OR " +
            "t.toAccount IN :toAccounts " +
            "ORDER BY t.timestamp DESC")
    List<Transaction> findByFromAccountInOrToAccountInOrderByCreatedAtDesc(
            @Param("fromAccounts") List<com.titan.titancorebanking.model.Account> fromAccounts,
            @Param("toAccounts") List<com.titan.titancorebanking.model.Account> toAccounts
    );

    // ✅ PHASE 6 TASK 8: Find high-value transactions for AML reporting
    @Query("SELECT t FROM Transaction t WHERE " +
            "t.amount >= :threshold AND " +
            "t.timestamp BETWEEN :startDate AND :endDate " +
            "ORDER BY t.timestamp DESC")
    List<Transaction> findByAmountGreaterThanEqualAndTimestampBetween(
            @Param("threshold") java.math.BigDecimal threshold,
            @Param("startDate") java.time.LocalDateTime startDate,
            @Param("endDate") java.time.LocalDateTime endDate
    );
}