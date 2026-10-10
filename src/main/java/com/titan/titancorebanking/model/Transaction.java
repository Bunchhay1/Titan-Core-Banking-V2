package com.titan.titancorebanking.model;

import com.titan.titancorebanking.enums.TransactionStatus; // ✅ IMPORT ENUM
import com.titan.titancorebanking.enums.TransactionType;   // ✅ IMPORT ENUM
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ✅ TITAN STANDARD: Idempotency key for duplicate prevention
    @Column(unique = true)
    private String idempotencyKey;

    // ✅ Transaction reference for tracking
    @Column(name = "transaction_reference")
    private String transactionReference;

    // ✅ Now this will correctly point to the ENUM
    @Enumerated(EnumType.STRING)
    private TransactionType transactionType;

    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private TransactionStatus status;

    private String note;

    private LocalDateTime timestamp;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_account_id")
    private Account fromAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_account_id")
    private Account toAccount;
    
    @Transient
    public String getToAccountNumber() {
        return toAccount != null ? toAccount.getAccountNumber() : null;
    }
    
    @Transient
    public Account getAccount() {
        return fromAccount != null ? fromAccount : toAccount;
    }
    
    @Transient
    public TransactionType getType() {
        return transactionType;
    }
}