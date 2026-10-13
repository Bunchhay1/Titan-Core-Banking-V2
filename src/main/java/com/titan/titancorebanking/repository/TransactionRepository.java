package com.titan.titancorebanking.repository;

import com.titan.titancorebanking.model.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    @EntityGraph(attributePaths = {"fromAccount", "toAccount", "fromAccount.user", "toAccount.user"})
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    boolean existsByIdempotencyKey(String idempotencyKey);

    Optional<Transaction> findByTransactionReference(String transactionReference);

    List<Transaction> findByFromAccountIdAndStatus(Long fromAccountId, com.titan.titancorebanking.enums.TransactionStatus status);

    @Query(value = """
        SELECT t FROM Transaction t
        LEFT JOIN FETCH t.fromAccount fa
        LEFT JOIN FETCH t.toAccount ta
        WHERE (fa.user.username = :username OR ta.user.username = :username)
        ORDER BY t.timestamp DESC, t.id DESC
    """,
    countQuery = """
        SELECT COUNT(t) FROM Transaction t
        LEFT JOIN t.fromAccount fa
        LEFT JOIN t.toAccount ta
        WHERE (fa.user.username = :username OR ta.user.username = :username)
    """)
    Page<Transaction> findAllByUser(@Param("username") String username, Pageable pageable);

    @Query("""
        SELECT t FROM Transaction t
        LEFT JOIN FETCH t.fromAccount fa
        LEFT JOIN FETCH t.toAccount ta
        WHERE (fa.user.username = :username OR ta.user.username = :username)
        ORDER BY t.timestamp DESC, t.id DESC
    """)
    List<Transaction> findAllByUser(@Param("username") String username);

    /**
     * Returns the single most-recent transaction for a user without loading the full history.
     * Replaces the previous pattern of calling {@code findAllByUser()} and taking the last
     * element, which caused an O(N) unbounded read for high-volume users.
     */
    @Query("""
        SELECT t FROM Transaction t
        LEFT JOIN FETCH t.fromAccount fa
        LEFT JOIN FETCH t.toAccount ta
        WHERE (fa.user.username = :username OR ta.user.username = :username)
        ORDER BY t.timestamp DESC, t.id DESC
        LIMIT 1
    """)
    Optional<Transaction> findLatestByUser(@Param("username") String username);

    @Query("""
        SELECT t FROM Transaction t 
        WHERE t.fromAccount.id = :accountId OR t.toAccount.id = :accountId 
        ORDER BY t.timestamp DESC
    """)
    List<Transaction> findTop10ByFromAccountIdOrToAccountIdOrderByTimestampDesc(
            @Param("accountId") Long fromAccountId,
            @Param("accountId") Long toAccountId
    );

    /**
     * Sums the total amount transferred OUT from a given account since a given timestamp.
     * Used by the daily transfer limit guard in TransactionService.
     * Only counts SUCCESS and PROCESSING statuses to avoid penalising failed attempts.
     */
    @Query("""
        SELECT COALESCE(SUM(t.amount), 0)
        FROM Transaction t
        WHERE t.fromAccount.id = :accountId
          AND t.transactionType = com.titan.titancorebanking.enums.TransactionType.TRANSFER
          AND t.status IN (
                com.titan.titancorebanking.enums.TransactionStatus.SUCCESS,
                com.titan.titancorebanking.enums.TransactionStatus.PROCESSING
              )
          AND t.timestamp >= :since
    """)
    BigDecimal sumOutboundTransfersSince(
            @Param("accountId") Long accountId,
            @Param("since") LocalDateTime since
    );

    /**
     * Sums the total amount withdrawn from a given account since a given timestamp.
     * Used by the daily withdrawal limit guard in TransactionService.
     * Only counts SUCCESS and PROCESSING statuses to avoid penalising failed attempts.
     */
    @Query("""
        SELECT COALESCE(SUM(t.amount), 0)
        FROM Transaction t
        WHERE t.fromAccount.id = :accountId
          AND t.transactionType = com.titan.titancorebanking.enums.TransactionType.WITHDRAWAL
          AND t.status IN (
                com.titan.titancorebanking.enums.TransactionStatus.SUCCESS,
                com.titan.titancorebanking.enums.TransactionStatus.PROCESSING
              )
          AND t.timestamp >= :since
    """)
    BigDecimal sumOutboundWithdrawalsSince(
            @Param("accountId") Long accountId,
            @Param("since") LocalDateTime since
    );
}