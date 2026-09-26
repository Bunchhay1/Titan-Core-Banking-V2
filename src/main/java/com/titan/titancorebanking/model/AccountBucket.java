package com.titan.titancorebanking.model;

import com.titan.titancorebanking.enums.Currency;
import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "account_buckets", indexes = {
        @Index(name = "idx_account_buckets_parent_idx", columnList = "parent_account_id, bucket_index", unique = true)
})
public class AccountBucket implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_account_id", nullable = false)
    private Account parentAccount;

    @Column(name = "bucket_index", nullable = false)
    private int bucketIndex;

    @Column(nullable = false, precision = 30, scale = 2)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
