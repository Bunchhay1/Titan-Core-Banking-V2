package com.titan.titancorebanking.repository;

import com.titan.titancorebanking.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    // Append-only audit repository. Deletions should be explicitly blocked via DB triggers.
}