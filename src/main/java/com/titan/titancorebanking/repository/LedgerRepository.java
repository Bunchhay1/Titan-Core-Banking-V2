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

    @Query("""
        SELECT SUM(CASE WHEN l.entryType = 'CREDIT' THEN l.amount ELSE -l.amount END) 
        FROM LedgerEntry l 
        WHERE l.accountId = :accountId
    """)
    BigDecimal calculateAccountBalance(@Param("accountId") Long accountId);

    @Query(value = """
        SELECT * FROM ledger_entry 
        WHERE entry_date < :cutoffDate 
        ORDER BY id ASC 
        LIMIT :batchSize
    """, nativeQuery = true)
    List<LedgerEntry> findOlderThan(@Param("cutoffDate") LocalDateTime cutoffDate, @Param("batchSize") int batchSize);
}