package com.titan.promotions.clawback;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RewardClawbackRepository extends JpaRepository<RewardClawback, Long> {
    Optional<RewardClawback> findByOriginalTransactionId(Long transactionId);
    boolean existsByOriginalTransactionId(Long transactionId);
}
