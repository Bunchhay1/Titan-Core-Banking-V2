package com.titan.promotions.repository;

import com.titan.promotions.model.PromotionOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PromotionOutboxRepository extends JpaRepository<PromotionOutbox, Long> {
    
    List<PromotionOutbox> findByStatusOrderByCreatedAtAsc(PromotionOutbox.OutboxStatus status);
}
