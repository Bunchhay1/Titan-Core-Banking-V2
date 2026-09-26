package com.titan.titancorebanking.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AtmCode — One-time 12-digit code for cardless ATM withdrawal.
 *
 * Flow:
 *  1. User generates a code via mobile app (POST /api/v1/atm/generate)
 *     → balance is reserved immediately
 *  2. User enters the 12-digit code at the ATM (POST /api/v1/atm/redeem)
 *     → balance is deducted, code is marked USED
 *  3. Code expires after 10 minutes or one use, whichever is first.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "atm_codes",
    indexes = {
        @Index(name = "idx_atm_code_value",      columnList = "code"),
        @Index(name = "idx_atm_code_account_id",  columnList = "account_id"),
        @Index(name = "idx_atm_code_expires_at",  columnList = "expires_at")
    }
)
public class AtmCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The 12-digit one-time code shown to the customer. */
    @Column(nullable = false, unique = true, length = 12)
    private String code;

    /** Account from which cash will be dispensed. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    /** Amount the customer wants to withdraw. */
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AtmCodeStatus status;

    /** When the code becomes invalid regardless of use. */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** Set when the code is redeemed at the ATM. */
    @Column(name = "redeemed_at")
    private LocalDateTime redeemedAt;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** Optional: ATM terminal identifier for audit purposes. */
    @Column(name = "atm_terminal_id", length = 50)
    private String atmTerminalId;

    // -------------------------------------------------------------------------
    // Status enum — kept as inner class for locality; could be promoted to
    // com.titan.titancorebanking.enums if needed elsewhere.
    // -------------------------------------------------------------------------
    public enum AtmCodeStatus {
        /** Generated, not yet redeemed. */
        PENDING,
        /** Successfully redeemed at ATM. */
        USED,
        /** Expired before redemption. */
        EXPIRED,
        /** Cancelled by the customer before use. */
        CANCELLED
    }
}
