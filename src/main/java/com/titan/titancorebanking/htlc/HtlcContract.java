package com.titan.titancorebanking.htlc;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "htlc_contract", indexes = {
        @Index(name = "idx_htlc_hash_lock", columnList = "hashLock"),
        @Index(name = "idx_htlc_status_expires", columnList = "status, expiresAt")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HtlcContract {
    @Id
    @Column(length = 36)
    private String contractId;

    @Column(nullable = false)
    private Long senderAccountId;

    @Column(nullable = false)
    private Long receiverAccountId;

    @Column(nullable = false, precision = 30, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 64)
    private String hashLock;

    @Column(length = 128)
    private String preimage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HtlcStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private LocalDateTime settledAt;

    @Column(length = 500)
    private String memo;

    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); }

    public enum HtlcStatus { LOCKED, CLAIMED, EXPIRED, REFUNDED }
}