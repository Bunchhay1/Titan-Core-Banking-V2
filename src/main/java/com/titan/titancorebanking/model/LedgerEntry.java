package com.titan.titancorebanking.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "ledger_entry", indexes = {
    @Index(name = "idx_ledger_transaction", columnList = "transactionId"),
    @Index(name = "idx_ledger_account_date", columnList = "accountId, entryDate")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LedgerEntry {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private Long transactionId;
    
    @Column(nullable = false)
    private Long accountId;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EntryType entryType;
    
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;
    
    @Column(nullable = false)
    private LocalDateTime entryDate;
    
    @Column(length = 500)
    private String description;
    
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @Column(nullable = false, updatable = false)
    private String createdBy;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
    
    public enum EntryType {
        DEBIT,  // Money out
        CREDIT  // Money in
    }
}
