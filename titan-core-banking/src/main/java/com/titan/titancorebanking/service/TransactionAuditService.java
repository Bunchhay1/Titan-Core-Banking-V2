package com.titan.titancorebanking.service;

import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.enums.TransactionStatus;
import com.titan.titancorebanking.enums.TransactionType;
import com.titan.titancorebanking.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class TransactionAuditService {

    private final TransactionRepository transactionRepository;

    // 🔥 REQUIRES_NEW: Log នេះនឹង Save ជានិច្ច ទោះបីជា Transaction ធំ Rollback ក៏ដោយ
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transaction saveAuditLog(Account from, Account to, BigDecimal amount,
                                    TransactionType type, TransactionStatus status, String note) {

        // Generate unique transaction reference
        String txRef = "TX" + System.currentTimeMillis() + (int)(Math.random() * 1000);

        Transaction tx = Transaction.builder()
                .transactionType(type)
                .fromAccount(from) // Can be null for Deposit
                .toAccount(to)     // Can be null for Withdrawal
                .amount(amount)
                .status(status)
                .note(note)
                .timestamp(LocalDateTime.now())
                .transactionReference(txRef) // ✅ Set transaction reference
                .build();

        return transactionRepository.save(tx);
    }
}