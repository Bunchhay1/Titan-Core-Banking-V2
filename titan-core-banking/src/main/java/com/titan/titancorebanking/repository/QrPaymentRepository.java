package com.titan.titancorebanking.repository;

import com.titan.titancorebanking.model.QrPayment;
import com.titan.titancorebanking.model.QrPayment.QrStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface QrPaymentRepository extends JpaRepository<QrPayment, Long> {

    // ✅ Look up a QR by its token (called when payer scans)
    Optional<QrPayment> findByQrCode(String qrCode);

    // ✅ All QRs created for a specific payee account
    List<QrPayment> findByPayeeAccount_AccountNumberOrderByCreatedAtDesc(String accountNumber);

    // ✅ Find only pending QRs (for expiry check, polling, etc.)
    List<QrPayment> findByStatus(QrStatus status);

    // ✅ Bulk-expire QRs whose TTL has passed
    @Modifying
    @Query("UPDATE QrPayment q SET q.status = 'EXPIRED' " +
           "WHERE q.status = 'PENDING' AND q.expiresAt < :now")
    int expireStaleQrCodes(@Param("now") LocalDateTime now);

    // ✅ Check if a QR code already exists (used during token generation to guarantee uniqueness)
    boolean existsByQrCode(String qrCode);

    // ✅ Find a persistent account QR by account number + note marker (for get-or-create logic)
    Optional<QrPayment> findByPayeeAccount_AccountNumberAndNote(String accountNumber, String note);
}
