package com.titan.titancorebanking.repository;

import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.User;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    boolean existsByAccountNumber(String accountNumber);

    Optional<Account> findByAccountNumber(String accountNumber);

    List<Account> findByUserUsername(String username);

    List<Account> findByUserId(Long userId);

    int countByUser(User user);

    /**
     * Pessimistic write lock for critical financial operations.
     * Enforces a 3-second timeout to prevent indefinite thread blocking during high contention.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000")})
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdWithLock(@Param("id") Long id);

    /**
     * Pessimistic write lock by account number.
     * Enforces a 3-second timeout.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000")})
    @Query("SELECT a FROM Account a WHERE a.accountNumber = :accountNumber")
    Optional<Account> findByAccountNumberWithLock(@Param("accountNumber") String accountNumber);

    /**
     * Keyset pagination (Seek Method) support for BatchScheduler.
     * Prevents missed or duplicated records when the dataset is modified concurrently during batch processing.
     */
    Page<Account> findByIdGreaterThanOrderByIdAsc(Long id, Pageable pageable);
}