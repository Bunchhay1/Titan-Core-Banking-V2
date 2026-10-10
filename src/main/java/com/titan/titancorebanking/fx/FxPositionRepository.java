package com.titan.titancorebanking.fx;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

public interface FxPositionRepository extends JpaRepository<FxHedgePosition, Long> {

    @Query("SELECT SUM(p.hedgedAmount) FROM FxHedgePosition p WHERE p.status = 'OPEN'")
    Optional<BigDecimal> sumPendingExposure();

    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO fx_hedge_position (from_currency, to_currency, hedged_amount, rate_at_hedge, hedged_at, status)
        VALUES (:from, :to, :amount, :rate, :hedgedAt, 'OPEN')
        ON CONFLICT (from_currency, to_currency) WHERE status = 'OPEN'
        DO UPDATE SET hedged_amount = :amount, rate_at_hedge = :rate, hedged_at = :hedgedAt
        """, nativeQuery = true)
    void upsertHedgePosition(String from, String to, BigDecimal amount, BigDecimal rate, Instant hedgedAt);
}
