package com.titan.titancorebanking.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@Entity @Table(name = "fixed_deposits")
public class FixedDeposit {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long accountId;
    private BigDecimal principalAmount;
    private BigDecimal maturityAmount;
    private Integer termMonths;
    private BigDecimal interestRate;
    private LocalDateTime maturityDate;
    private String status;
}