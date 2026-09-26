package com.titan.titancorebanking.controller;

import com.titan.titancorebanking.dto.request.TransactionRequest;
import com.titan.titancorebanking.model.ScheduledTransaction;
import com.titan.titancorebanking.repository.ScheduledTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/scheduled-transactions")
@RequiredArgsConstructor
public class ScheduledTransactionController {

    private final ScheduledTransactionRepository repository;

    @PostMapping
    public ResponseEntity<?> schedule(@RequestBody TransactionRequest request) {
        // Simple mapping for test
        var st = ScheduledTransaction.builder()
                .fromAccountId(Long.parseLong(request.fromAccountNumber() != null ? request.fromAccountNumber() : "0")) // Simplification
                .amount(request.amount())
                .scheduledDate(LocalDateTime.now().plusDays(1)) // Mock logic
                .status("PENDING")
                .build();
        return ResponseEntity.ok(repository.save(st));
    }
}