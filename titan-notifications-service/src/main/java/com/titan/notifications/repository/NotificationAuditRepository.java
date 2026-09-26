package com.titan.notifications.repository;

import com.titan.notifications.model.NotificationAudit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationAuditRepository extends JpaRepository<NotificationAudit, Long> {

    // ── iOS polling — IN_APP only, newest first, paginated ───────────────────
    List<NotificationAudit> findByAccountIdAndChannelOrderByAttemptedAtDesc(
            String accountId, String channel, Pageable pageable);

    // ── All records for an account, newest first ──────────────────────────────
    List<NotificationAudit> findByAccountIdOrderByAttemptedAtDesc(
            String accountId, Pageable pageable);

    // ── Transaction lookup (debugging / admin) ────────────────────────────────
    List<NotificationAudit> findByTransactionIdOrderByAttemptedAtDesc(String transactionId);

    // ── Legacy ────────────────────────────────────────────────────────────────
    List<NotificationAudit> findByTransactionId(String transactionId);
    List<NotificationAudit> findByAccountId(String accountId);
}
