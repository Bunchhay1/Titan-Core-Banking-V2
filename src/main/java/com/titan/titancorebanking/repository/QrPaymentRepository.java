package com.titan.titancorebanking.repository;

import com.titan.titancorebanking.model.QrPayment;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface QrPaymentRepository extends JpaRepository<QrPayment, Long> {

    Optional<QrPayment> findByQrCode(String qrCode);

    boolean existsByQrCode(String qrCode);

    List<QrPayment> findByPayeeAccount_AccountNumberOrderByCreatedAtDesc(String accountNumber);

    Optional<QrPayment> findByPayeeAccount_AccountNumberAndNote(String accountNumber, String note);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000")})
    @Query("SELECT q FROM QrPayment q WHERE q.qrCode = :qrCode")
    Optional<QrPayment> findByQrCodeWithLock(@Param("qrCode") String qrCode);

    @Modifying
    @Query("UPDATE QrPayment q SET q.status = 'EXPIRED' WHERE q.status = 'PENDING' AND q.expiresAt < :now AND q.note != 'ACCOUNT_QR'")
    int expireStaleQrCodes(@Param("now") LocalDateTime now);
}