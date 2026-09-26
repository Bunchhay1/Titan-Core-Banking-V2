package com.titan.notifications.controller;

import com.titan.notifications.model.NotificationAudit;
import com.titan.notifications.repository.NotificationAuditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AuditController — serves notification history to the iOS app.
 *
 * Primary endpoint for iOS:
 *   GET /api/audit/user/{username}?limit=20
 *
 * Returns only IN_APP records, deduplicated by transactionId (newest first).
 * This prevents the iOS app from showing duplicate or FAILED/EXTERNAL entries.
 */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final NotificationAuditRepository repository;

    /**
     * GET /api/audit/user/{username}?limit=20
     *
     * Primary iOS endpoint.
     * - Only returns channel=IN_APP records (clean, no EXTERNAL/FAILED noise)
     * - Deduplicated by transactionId (one entry per transaction)
     * - Newest first
     * - Username trimmed to guard against whitespace bugs
     */
    @GetMapping("/user/{username}")
    public ResponseEntity<List<NotificationAudit>> getByUsername(
            @PathVariable String username,
            @RequestParam(defaultValue = "20") int limit) {

        int safeLimit = Math.min(Math.max(limit, 1), 100);
        String cleanUsername = username.trim();

        // Fetch more than limit to account for deduplication
        List<NotificationAudit> all = repository.findByAccountIdAndChannelOrderByAttemptedAtDesc(
                cleanUsername, "IN_APP",
                PageRequest.of(0, safeLimit * 3, Sort.by(Sort.Direction.DESC, "attemptedAt")));

        // Deduplicate: keep distinct messages per transactionId
        List<NotificationAudit> deduped = all.stream()
                .collect(Collectors.toMap(
                        a -> (a.getTransactionId() != null ? a.getTransactionId() : "") + ":" + (a.getMessage() != null ? a.getMessage() : ""),
                        a -> a,
                        (existing, replacement) -> existing,   // keep newest (list is DESC)
                        LinkedHashMap::new))
                .values().stream()
                .limit(safeLimit)
                .collect(Collectors.toList());

        return ResponseEntity.ok(deduped);
    }

    /**
     * GET /api/audit/transaction/{transactionId}
     * Full detail for one transaction — for admin/debugging.
     */
    @GetMapping("/transaction/{transactionId}")
    public ResponseEntity<List<NotificationAudit>> getByTransaction(
            @PathVariable String transactionId) {
        return ResponseEntity.ok(
                repository.findByTransactionIdOrderByAttemptedAtDesc(transactionId));
    }

    /**
     * GET /api/audit/account/{accountId}
     * Legacy endpoint — kept for backward compatibility with older iOS builds.
     * Returns IN_APP only, deduplicated, newest first.
     */
    @GetMapping("/account/{accountId}")
    public ResponseEntity<List<NotificationAudit>> getByAccount(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "20") int limit) {

        int safeLimit = Math.min(Math.max(limit, 1), 100);
        String clean = accountId.trim();

        List<NotificationAudit> all = repository.findByAccountIdAndChannelOrderByAttemptedAtDesc(
                clean, "IN_APP",
                PageRequest.of(0, safeLimit * 3, Sort.by(Sort.Direction.DESC, "attemptedAt")));

        List<NotificationAudit> deduped = all.stream()
                .collect(Collectors.toMap(
                        a -> (a.getTransactionId() != null ? a.getTransactionId() : "") + ":" + (a.getMessage() != null ? a.getMessage() : ""),
                        a -> a,
                        (existing, replacement) -> existing,
                        LinkedHashMap::new))
                .values().stream()
                .limit(safeLimit)
                .collect(Collectors.toList());

        return ResponseEntity.ok(deduped);
    }
}
