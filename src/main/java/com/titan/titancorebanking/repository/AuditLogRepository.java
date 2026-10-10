package com.titan.titancorebanking.repository;

import com.titan.titancorebanking.model.AuditLog; // ✅ Correct Import
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}