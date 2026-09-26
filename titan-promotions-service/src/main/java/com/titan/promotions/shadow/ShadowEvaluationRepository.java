package com.titan.promotions.shadow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

@Repository
public interface ShadowEvaluationRepository extends JpaRepository<ShadowEvaluation, Long> {
    @Query("SELECT SUM(s.theoreticalPayout) FROM ShadowEvaluation s WHERE s.ruleId = :ruleId AND s.matched = true")
    BigDecimal calculateTotalCost(Long ruleId);
    
    @Query("SELECT COUNT(s) FROM ShadowEvaluation s WHERE s.ruleId = :ruleId AND s.matched = true")
    Long countMatches(Long ruleId);
}
