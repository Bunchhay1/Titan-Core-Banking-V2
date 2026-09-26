package com.titan.promotions.shadow;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "shadow_evaluations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShadowEvaluation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private Long ruleId;
    
    @Column(nullable = false)
    private Long transactionId;
    
    @Column(nullable = false)
    private Long accountId;
    
    private Boolean matched;
    
    @Column(precision = 19, scale = 2)
    private BigDecimal theoreticalPayout;
    
    @Column(nullable = false)
    private LocalDateTime evaluatedAt;
    
    @Column(length = 1000)
    private String ruleExpression;
}
