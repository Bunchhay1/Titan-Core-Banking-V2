package com.titan.titancorebanking.repository;

import com.titan.titancorebanking.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    boolean existsByIdempotencyKey(String idempotencyKey);

    Optional<Transaction> findByTransactionReference(String transactionReference);

    @Query("""
        SELECT t FROM Transaction t 
        LEFT JOIN FETCH t.fromAccount fa 
        LEFT JOIN FETCH t.toAccount ta 
        WHERE (fa.user.username = :username OR ta.user.username = :username) 
        ORDER BY t.timestamp DESC
    """)
    List<Transaction> findAllByUser(@Param("username") String username);

    @Query("""
        SELECT t FROM Transaction t 
        WHERE t.fromAccount.id = :accountId OR t.toAccount.id = :accountId 
        ORDER BY t.timestamp DESC
    """)
    List<Transaction> findTop10ByFromAccountIdOrToAccountIdOrderByTimestampDesc(
            @Param("accountId") Long fromAccountId,
            @Param("accountId") Long toAccountId
    );
}