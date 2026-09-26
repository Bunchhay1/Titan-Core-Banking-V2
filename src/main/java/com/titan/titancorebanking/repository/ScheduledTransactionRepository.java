package com.titan.titancorebanking.repository;
import com.titan.titancorebanking.model.ScheduledTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ScheduledTransactionRepository extends JpaRepository<ScheduledTransaction, Long> {}