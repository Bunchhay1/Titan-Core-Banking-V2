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
    @Query("SELECT b FROM AccountBucket b WHERE b.id = :id")
    Optional<AccountBucket> findByIdWithLock(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000")})
    @Query("SELECT b FROM AccountBucket b WHERE b.parentAccount.id = :parentAccountId AND b.bucketIndex = :bucketIndex")
    Optional<AccountBucket> findByParentAccountIdAndBucketIndexWithLock(
            @Param("parentAccountId") Long parentAccountId,
            @Param("bucketIndex") int bucketIndex);

    Optional<AccountBucket> findByParentAccountIdAndBucketIndex(Long parentAccountId, int bucketIndex);

    @Query("SELECT COALESCE(SUM(b.balance), 0) FROM AccountBucket b WHERE b.parentAccount.id = :parentAccountId")
    BigDecimal sumBucketBalancesByParentAccountId(@Param("parentAccountId") Long parentAccountId);
}
