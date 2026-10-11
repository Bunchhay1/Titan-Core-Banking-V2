package com.titan.titancorebanking.model;

import com.titan.titancorebanking.enums.TransactionStatus;
import com.titan.titancorebanking.enums.TransactionType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "transactions", indexes = {
        @Index(name = "idx_tx_idempotency", columnList = "idempotencyKey", unique = true),
        @Index(name = "idx_tx_reference", columnList = "transaction_reference", unique = true),
        @Index(name = "idx_tx_from_account", columnList = "from_account_id"),
        @Index(name = "idx_tx_to_account", columnList = "to_account_id"),
        @Index(name = "idx_tx_timestamp", columnList = "timestamp")
})
@DynamicUpdate
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, length = 128)
    private String idempotencyKey;

    @Column(name = "transaction_reference", unique = true, nullable = false, length = 50)
    private String transactionReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TransactionType transactionType;

    @Column(nullable = false, precision = 30, scale = 4)
    private BigDecimal amount;

    /**
     * Fee charged for this transaction.
     * ZERO for deposits and withdrawals; the strategy-calculated fee for transfers.
     * Recorded so the response, ledger, and reconciliation can verify:
     *   fromAccount debit == amount + fee
     *   toAccount credit  == amount (no fee)
     *   ledger debit entry == amount + fee
     */
    @Builder.Default
    @Column(nullable = false, precision = 30, scale = 4)
    private BigDecimal fee = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionStatus status;

    @Column(length = 255)
    private String note;

    @Column(nullable = false, updatable = false)
    private LocalDateTime timestamp;

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_account_id")
    private Account fromAccount;

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_account_id")
    private Account toAccount;
}