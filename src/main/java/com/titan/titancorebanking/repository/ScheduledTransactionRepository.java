package com.titan.titancorebanking.repository;

import com.titan.titancorebanking.model.ScheduledTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ScheduledTransactionRepository extends JpaRepository<ScheduledTransaction, Long> {

    List<ScheduledTransaction> findByStatusAndScheduledDateLessThanEqual(String status, LocalDateTime scheduledDate);
}