package com.titan.titancorebanking.repository;

import com.titan.titancorebanking.model.AccountBucket;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface AccountBucketRepository extends JpaRepository<AccountBucket, Long> {

    List<AccountBucket> findByParentAccountId(Long parentAccountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000")})
    @Query("SELECT b FROM AccountBucket b WHERE b.parentAccount.id = :parentAccountId ORDER BY b.bucketIndex ASC")
    List<AccountBucket> findByParentAccountIdWithLock(@Param("parentAccountId") Long parentAccountId);

    @Query("SELECT DISTINCT a.parentAccount.id FROM AccountBucket a WHERE a.balance > 0")
    List<Long> findDistinctParentAccountIds();

    /**
     * Pessimistically locks a specific partition bucket for a merchant account.
     * Prevents update-loss without blocking the other 7 partitions.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000")})
    @Query("SELECT b FROM AccountBucket b WHERE b.parentAccount.id = :parentAccountId AND b.bucketIndex = :bucketIndex")
    Optional<AccountBucket> findByParentAccountIdAndBucketIndexWithLock(
            @Param("parentAccountId") Long parentAccountId,
            @Param("bucketIndex") int bucketIndex
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000")})
    @Query("SELECT b FROM AccountBucket b WHERE b.id = :id")
    Optional<AccountBucket> findByIdWithLock(@Param("id") Long id);

    @Query("SELECT SUM(b.balance) FROM AccountBucket b WHERE b.parentAccount.id = :parentAccountId")
    BigDecimal sumBucketBalancesByParentAccountId(@Param("parentAccountId") Long parentAccountId);

    /**
     * Atomically inserts a bucket row with ON CONFLICT DO NOTHING.
     * Prevents unique constraint race conditions under concurrent initializations.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query(value = """
        INSERT INTO account_buckets (parent_account_id, bucket_index, balance, currency, updated_at)
        VALUES (:parentAccountId, :bucketIndex, 0.00, :currency, NOW())
        ON CONFLICT (parent_account_id, bucket_index) DO NOTHING
    """, nativeQuery = true)
    void insertBucketIfNotExists(
            @Param("parentAccountId") Long parentAccountId,
            @Param("bucketIndex") int bucketIndex,
            @Param("currency") String currency
    );
}