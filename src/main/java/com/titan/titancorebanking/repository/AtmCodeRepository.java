package com.titan.titancorebanking.repository;

import com.titan.titancorebanking.model.AtmCode;
import com.titan.titancorebanking.model.AtmCode.AtmCodeStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AtmCodeRepository extends JpaRepository<AtmCode, Long> {

    /**
     * Pessimistic-write lock so the ATM terminal and expiry-sweep
     * job cannot race against each other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AtmCode a WHERE a.code = :code")
    Optional<AtmCode> findByCodeWithLock(@Param("code") String code);

    /** Read-only lookup (no lock). */
    Optional<AtmCode> findByCode(String code);

    /** Find all pending codes for a specific account (to cancel on new generation). */
    List<AtmCode> findByAccount_IdAndStatus(Long accountId, AtmCodeStatus status);

    /** Expired codes that still have PENDING status — used by the cleanup scheduler. */
    @Query("SELECT a FROM AtmCode a WHERE a.status = 'PENDING' AND a.expiresAt < :now")
    List<AtmCode> findExpiredPendingCodes(@Param("now") LocalDateTime now);

    /**
     * Bulk-expire codes in one UPDATE to keep the cleanup job lightweight.
     */
    @Modifying
    @Query("UPDATE AtmCode a SET a.status = 'EXPIRED' WHERE a.status = 'PENDING' AND a.expiresAt < :now")
    int expirePendingCodes(@Param("now") LocalDateTime now);
}
