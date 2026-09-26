package com.titan.titancorebanking.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@Entity @Table(name = "scheduled_transactions")
public class ScheduledTransaction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_account_id", nullable = false)
    private Long fromAccountId;

    @Column(name = "to_account_number", nullable = false)
    private String toAccountNumber;

    private BigDecimal amount;

    @Column(name = "frequency", nullable = false)
    private String frequency;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "start_date", nullable = false)
    private LocalDateTime startDate;

    @Column(name = "next_execution_date", nullable = false)
    private LocalDateTime scheduledDate;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}