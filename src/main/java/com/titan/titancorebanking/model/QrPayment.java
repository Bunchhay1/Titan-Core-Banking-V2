package com.titan.titancorebanking.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ✅ TITAN STANDARD: QR Payment entity
 * Stores QR codes generated for receiving payments and records payments made via QR.
 *
 * Flow:
 *   1. Payee calls /api/v1/qr/generate → creates a PENDING QrPayment, returns QR image
 *   2. Payer scans QR → calls /api/v1/qr/pay   → resolves qrCode, moves funds, status → COMPLETED
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "qr_payments", indexes = {
        @Index(name = "idx_qr_payments_code",   columnList = "qr_code",   unique = true),
        @Index(name = "idx_qr_payments_status",  columnList = "status"),
        @Index(name = "idx_qr_payments_account", columnList = "payee_account_id")
})
public class QrPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ✅ Unique QR token embedded in the QR image
    @Column(name = "qr_code", nullable = false, unique = true, length = 64)
    private String qrCode;

    // ✅ Account that will receive the money
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payee_account_id", nullable = false)
    private Account payeeAccount;

    // ✅ Account that paid (null until paid)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payer_account_id")
    private Account payerAccount;

    // ✅ Optional fixed amount (null = open amount, payer enters amount)
    @Column(precision = 20, scale = 2)
    private BigDecimal amount;

    // ✅ Currency (inherited from payee account if null)
    @Column(length = 3)
    private String currency;

    @Column(length = 255)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QrStatus status;

    // ✅ Reference to actual settled transaction
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id")
    private Transaction transaction;

    // ✅ QR valid until this timestamp (default: +15 minutes)
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    // ─── Inner enum ──────────────────────────────────────────────────────────────
    public enum QrStatus {
        PENDING,    // QR generated, waiting for payment
        COMPLETED,  // Payment received
        EXPIRED,    // TTL elapsed
        CANCELLED   // Payee cancelled
    }
}
