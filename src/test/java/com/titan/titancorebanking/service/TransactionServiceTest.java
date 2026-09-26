package com.titan.titancorebanking.service;

import com.titan.titancorebanking.dto.request.TransactionRequest;
import com.titan.riskengine.RiskCheckResponse;
import com.titan.titancorebanking.enums.TransactionType;
import com.titan.titancorebanking.enums.AccountType;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.model.User;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.TransactionRepository;
import com.titan.titancorebanking.service.imple.ExchangeRateService;
import com.titan.titancorebanking.failsafe.DeadMansSwitchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

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
    @Mock private TransactionAuditService auditService;
    @Mock private EventPublisherService eventPublisherService;
    @Mock private DoubleEntryService doubleEntryService;
    @Mock private IdempotencyService idempotencyService;
    @Mock private ExchangeRateService exchangeRateService;
    @Mock private DeadMansSwitchService deadMansSwitchService;
    @Mock private AccountBucketService accountBucketService;

    @InjectMocks
    private TransactionService transactionService;

    @Test
    void transfer_ShouldSuccess_WhenValid() {
        String username = "alice";
        User user = User.builder().id(10L).username(username).pin("hash").build();
        Account from = Account.builder().id(1L).accountNumber("001202611111").accountType(AccountType.SAVINGS).balance(new BigDecimal("1000.00")).user(user).build();
        Account to = Account.builder().id(2L).accountNumber("001202622222").accountType(AccountType.SAVINGS).balance(new BigDecimal("500.00")).build();

        TransactionRequest req = new TransactionRequest(
                "001202611111", "001202622222", new BigDecimal("100.00"), "1234",
                null, null, null, null, null, null);

        RiskCheckResponse risk = RiskCheckResponse.newBuilder().setAction("ALLOW").build();

        when(deadMansSwitchService.isLockdownActive()).thenReturn(false);
        when(accountRepository.findByAccountNumber("001202611111")).thenReturn(Optional.of(from));
        when(accountRepository.findByAccountNumber("001202622222")).thenReturn(Optional.of(to));
        when(accountRepository.findByIdWithLock(1L)).thenReturn(Optional.of(from));
        when(accountRepository.findByIdWithLock(2L)).thenReturn(Optional.of(to));
        when(passwordEncoder.matches(any(), any())).thenReturn(true);
        when(riskEngineGrpcService.analyzeTransaction(any(), anyDouble())).thenReturn(risk);
        when(auditService.saveAuditLog(any(), any(), any(), any(), any(), any()))
                .thenReturn(new Transaction());

        Transaction result = transactionService.transfer(req, username);

        assertNotNull(result);
        assertEquals(new BigDecimal("899.50"), from.getBalance());
        verify(accountRepository).save(from);
        verify(accountBucketService).creditBucket(eq(2L), anyInt(), any());
    }
}
