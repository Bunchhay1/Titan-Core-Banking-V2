package com.titan.titancorebanking.service;

import com.titan.titancorebanking.dto.request.TransactionRequest;
import com.titan.riskengine.RiskCheckResponse; // ✅ gRPC Version
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.User;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private RiskEngineGrpcService riskEngineGrpcService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private TransactionService transactionService;

    @Test
    void transfer_ShouldSuccess_WhenValid() {
        String username = "alice";
        User user = User.builder().username(username).pin("hash").build();
        Account from = Account.builder().accountNumber("111").balance(new BigDecimal("1000")).user(user).build();
        Account to = Account.builder().accountNumber("222").balance(new BigDecimal("500")).build();

        TransactionRequest req = new TransactionRequest(
                "111", "222", new BigDecimal("100"), "1234",
                null, null, null, null, null, null);

        // ✅ Build gRPC Response
        RiskCheckResponse risk = RiskCheckResponse.newBuilder().setAction("ALLOW").build();

        when(accountRepository.findByAccountNumber("111")).thenReturn(Optional.of(from));
        when(accountRepository.findByAccountNumber("222")).thenReturn(Optional.of(to));
        when(passwordEncoder.matches(any(), any())).thenReturn(true);
        when(riskEngineGrpcService.analyzeTransaction(anyString(), anyDouble())).thenReturn(risk);

        transactionService.transfer(req, username);

        assertEquals(new BigDecimal("900.00"), from.getBalance());
        verify(transactionRepository).save(any());
    }
}
