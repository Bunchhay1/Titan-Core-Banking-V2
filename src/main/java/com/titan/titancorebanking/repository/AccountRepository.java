package com.titan.titancorebanking.repository;

import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.User;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByAccountNumber(String accountNumber);

    List<Account> findByUserUsername(String username);

    int countByUser(User user);

    boolean existsByAccountNumber(String accountNumber);

    List<Account> findByUserId(Long userId);

    // STAFF ENGINEER FIX: Push-down filter to DB level to prevent massive I/O waste
    Slice<Account> findByBalanceGreaterThan(BigDecimal balance, Pageable pageable);

    // STAFF ENGINEER FIX: Chunked retrieval for reconciliation to prevent OOM
    @Query("SELECT a FROM Account a ORDER BY a.id ASC")
    Slice<Account> findAllAccountsForReconciliation(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000")})
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdWithLock(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000")})
    @Query("SELECT a FROM Account a WHERE a.accountNumber = :accountNumber")
    Optional<Account> findByAccountNumberWithLock(@Param("accountNumber") String accountNumber);
}