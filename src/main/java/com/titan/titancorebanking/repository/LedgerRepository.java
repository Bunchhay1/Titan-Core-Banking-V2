package com.titan.titancorebanking.repository;

import com.titan.titancorebanking.model.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface LedgerRepository extends JpaRepository<LedgerEntry, Long> {
    
    List<LedgerEntry> findByTransactionId(Long transactionId);
    
    List<LedgerEntry> findByAccountIdOrderByEntryDateDesc(Long accountId);
    
    @Query("SELECT SUM(CASE WHEN l.entryType = 'DEBIT' THEN -l.amount ELSE l.amount END) " +
           "FROM LedgerEntry l WHERE l.accountId = :accountId")
    BigDecimal calculateAccountBalance(@Param("accountId") Long accountId);
    
    @Query("SELECT SUM(CASE WHEN l.entryType = 'DEBIT' THEN -l.amount ELSE l.amount END) " +
           "FROM LedgerEntry l WHERE l.accountId = :accountId AND l.entryDate <= :asOfDate")
    BigDecimal calculateBalanceAsOf(@Param("accountId") Long accountId, 
                                    @Param("asOfDate") LocalDateTime asOfDate);

    // Task 5: Merkle pruning — fetch old entries in batches
    @Query(value = "SELECT * FROM ledger_entry WHERE entry_date < :cutoff ORDER BY id LIMIT :limit",
           nativeQuery = true)
    List<LedgerEntry> findOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
