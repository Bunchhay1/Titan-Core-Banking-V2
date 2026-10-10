package com.titan.titancorebanking.fx;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "fx_hedge_position")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class FxHedgePosition {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 3)
    private String fromCurrency;

    @Column(nullable = false, length = 3)
    private String toCurrency;

    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal hedgedAmount;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal rateAtHedge;

    @Column(nullable = false)
    private Instant hedgedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HedgeStatus status;

    public enum HedgeStatus { OPEN, CLOSED, EXPIRED }
}
