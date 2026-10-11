package com.titan.titancorebanking.failsafe;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

@Repository
public interface LockdownAuditRepository extends JpaRepository<LockdownAuditEntry, Long> {
    @Override @Modifying default void delete(LockdownAuditEntry entity) {
        throw new UnsupportedOperationException("Lockdown audit is immutable");
    }
    @Override @Modifying default void deleteAll() {
        throw new UnsupportedOperationException("Lockdown audit is immutable");
    }
}