package com.titan.titancorebanking.repository;

import com.titan.titancorebanking.model.QrPayment;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface QrPaymentRepository extends JpaRepository<QrPayment, Long> {

    boolean existsByQrCode(String qrCode);

    Optional<QrPayment> findByQrCode(String qrCode);

    /**
     * Critical guard against Double-Spend attacks.
     * Pessimistically locks the QR token row so that concurrent scans are serialized.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000")})
    @Query("SELECT q FROM QrPayment q WHERE q.qrCode = :qrCode")
    Optional<QrPayment> findByQrCodeWithLock(@Param("qrCode") String qrCode);

    Optional<QrPayment> findByPayeeAccount_AccountNumberAndNote(String accountNumber, String note);

    List<QrPayment> findByPayeeAccount_AccountNumberOrderByCreatedAtDesc(String accountNumber);

    /**
     * Batch expires stale QR codes efficiently at the database level, avoiding
     * the overhead of loading thousands of entities into the JVM persistence context.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("""
        UPDATE QrPayment q 
        SET q.status = com.titan.titancorebanking.model.QrPayment$QrStatus.EXPIRED 
        WHERE q.status = com.titan.titancorebanking.model.QrPayment$QrStatus.PENDING 
        AND q.expiresAt < :now AND q.note != 'ACCOUNT_QR'
    """)
    int expireStaleQrCodes(@Param("now") LocalDateTime now);
}