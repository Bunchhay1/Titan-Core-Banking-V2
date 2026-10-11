package com.titan.titancorebanking.repository;

import com.titan.titancorebanking.model.AtmCode;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AtmCodeRepository extends JpaRepository<AtmCode, Long> {

    Optional<AtmCode> findByCode(String code);

    List<AtmCode> findByAccount_IdAndStatus(Long accountId, AtmCode.AtmCodeStatus status);

    /**
     * Pessimistic Write Lock to prevent concurrent double-redemption of the same ATM code.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000")})
    @Query("SELECT a FROM AtmCode a WHERE a.code = :code")
    Optional<AtmCode> findByCodeWithLock(@Param("code") String code);

    /**
     * Batch expiration executed entirely inside the database engine to avoid JVM heap pressure.
     */
    @Modifying
    @Query("""
        UPDATE AtmCode a 
        SET a.status = com.titan.titancorebanking.model.AtmCode$AtmCodeStatus.EXPIRED 
        WHERE a.status = com.titan.titancorebanking.model.AtmCode$AtmCodeStatus.PENDING 
        AND a.expiresAt < :now
    """)
    int expirePendingCodes(@Param("now") LocalDateTime now);
}