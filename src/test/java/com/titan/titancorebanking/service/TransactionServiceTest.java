package com.titan.titancorebanking.service;

import com.titan.titancorebanking.dto.request.TransactionRequest;
import com.titan.titancorebanking.dto.response.TransactionResponse;
import com.titan.titancorebanking.mapper.TransactionMapper;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.repository.TransactionRepository;
import com.titan.titancorebanking.security.SecurityAuthorizationHelper;
import com.titan.titancorebanking.service.usecase.DepositUseCase;
import com.titan.titancorebanking.service.usecase.TransferUseCase;
import com.titan.titancorebanking.service.usecase.WithdrawUseCase;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused unit test suite for {@link TransactionService} facade delegation.
 * Executes without Spring application context or Testcontainers for sub-second execution.
 */
@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private TransferUseCase transferUseCase;

    @Mock
    private WithdrawUseCase withdrawUseCase;

    @Mock
    private DepositUseCase depositUseCase;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private TransactionMapper transactionMapper;

    @Mock
    private SecurityAuthorizationHelper securityHelper;

    @InjectMocks
    private TransactionService transactionService;

    private final TransactionRequest request = new TransactionRequest(
            "ACC-001", "ACC-002", new BigDecimal("100.00"), "1234", "Test note", null, null, "KEY-1"
    );

    private final TransactionResponse expectedResponse = new TransactionResponse(
            1L, "TRANSFER", new BigDecimal("100.00"), "ACC-001", "ACC-002",
            "SUCCESS", "Test note", LocalDateTime.now(), "USD", BigDecimal.ZERO, "TX-REF-1"
    );

    @Test
    @DisplayName("transfer(request) — delegates to TransferUseCase and maps to TransactionResponse")
    void transfer_singleArg_delegatesCorrectly() {
        Transaction mockEntity = new Transaction();
        when(transferUseCase.transfer(request)).thenReturn(mockEntity);
        when(transactionMapper.toResponse(mockEntity)).thenReturn(expectedResponse);

        TransactionResponse actual = transactionService.transfer(request);

        assertThat(actual).isEqualTo(expectedResponse);
        verify(transferUseCase).transfer(request);
        verify(transactionMapper).toResponse(mockEntity);
    }

    @Test
    @DisplayName("transfer(request, username) — delegates with explicit caller username")
    void transfer_twoArgs_delegatesCorrectly() {
        Transaction mockEntity = new Transaction();
        when(transferUseCase.transfer(request, "alice")).thenReturn(mockEntity);
        when(transactionMapper.toResponse(mockEntity)).thenReturn(expectedResponse);

        TransactionResponse actual = transactionService.transfer(request, "alice");

        assertThat(actual).isEqualTo(expectedResponse);
        verify(transferUseCase).transfer(request, "alice");
    }

    @Test
    @DisplayName("executeScheduledTransfer — delegates to TransferUseCase scheduled flow")
    void executeScheduledTransfer_delegatesCorrectly() {
        Transaction mockEntity = new Transaction();
        when(transferUseCase.executeScheduledTransfer(request, "alice")).thenReturn(mockEntity);
        when(transactionMapper.toResponse(mockEntity)).thenReturn(expectedResponse);

        TransactionResponse actual = transactionService.executeScheduledTransfer(request, "alice");

        assertThat(actual).isEqualTo(expectedResponse);
        verify(transferUseCase).executeScheduledTransfer(request, "alice");
    }

    @Test
    @DisplayName("executeSecureTransfer — backwards-compatible delegate routes to transfer")
    void executeSecureTransfer_delegatesToTransfer() {
        Transaction mockEntity = new Transaction();
        when(transferUseCase.transfer(request, "alice")).thenReturn(mockEntity);
        when(transactionMapper.toResponse(mockEntity)).thenReturn(expectedResponse);

        TransactionResponse actual = transactionService.executeSecureTransfer(request, "alice");

        assertThat(actual).isEqualTo(expectedResponse);
        verify(transferUseCase).transfer(request, "alice");
    }

    @Test
    @DisplayName("withdraw(request) — delegates to WithdrawUseCase and maps to DTO")
    void withdraw_singleArg_delegatesCorrectly() {
        Transaction mockEntity = new Transaction();
        when(withdrawUseCase.withdraw(request)).thenReturn(mockEntity);
        when(transactionMapper.toResponse(mockEntity)).thenReturn(expectedResponse);

        TransactionResponse actual = transactionService.withdraw(request);

        assertThat(actual).isEqualTo(expectedResponse);
        verify(withdrawUseCase).withdraw(request);
    }

    @Test
    @DisplayName("withdraw(request, username) — delegates with explicit username")
    void withdraw_twoArgs_delegatesCorrectly() {
        Transaction mockEntity = new Transaction();
        when(withdrawUseCase.withdraw(request, "alice")).thenReturn(mockEntity);
        when(transactionMapper.toResponse(mockEntity)).thenReturn(expectedResponse);

        TransactionResponse actual = transactionService.withdraw(request, "alice");

        assertThat(actual).isEqualTo(expectedResponse);
        verify(withdrawUseCase).withdraw(request, "alice");
    }

    @Test
    @DisplayName("deposit(request) — delegates to DepositUseCase and maps to DTO")
    void deposit_singleArg_delegatesCorrectly() {
        Transaction mockEntity = new Transaction();
        when(depositUseCase.deposit(request)).thenReturn(mockEntity);
        when(transactionMapper.toResponse(mockEntity)).thenReturn(expectedResponse);

        TransactionResponse actual = transactionService.deposit(request);

        assertThat(actual).isEqualTo(expectedResponse);
        verify(depositUseCase).deposit(request);
    }

    @Test
    @DisplayName("deposit(request, username) — delegates with explicit username")
    void deposit_twoArgs_delegatesCorrectly() {
        Transaction mockEntity = new Transaction();
        when(depositUseCase.deposit(request, "alice")).thenReturn(mockEntity);
        when(transactionMapper.toResponse(mockEntity)).thenReturn(expectedResponse);

        TransactionResponse actual = transactionService.deposit(request, "alice");

        assertThat(actual).isEqualTo(expectedResponse);
        verify(depositUseCase).deposit(request, "alice");
    }

    @Test
    @DisplayName("depositInternal — delegates with internal caller identity")
    void depositInternal_delegatesCorrectly() {
        Transaction mockEntity = new Transaction();
        when(depositUseCase.depositInternal(request, "SYSTEM_BATCH")).thenReturn(mockEntity);
        when(transactionMapper.toResponse(mockEntity)).thenReturn(expectedResponse);

        TransactionResponse actual = transactionService.depositInternal(request, "SYSTEM_BATCH");

        assertThat(actual).isEqualTo(expectedResponse);
        verify(depositUseCase).depositInternal(request, "SYSTEM_BATCH");
    }

    @Test
    @DisplayName("getTransactionHistory() — extracts authenticated principal and queries history")
    void getTransactionHistory_authenticatedPrincipal_delegatesCorrectly() {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("alice");
        when(securityHelper.requireAuthentication()).thenReturn(auth);

        Transaction entity = new Transaction();
        when(transactionRepository.findAllByUser("alice")).thenReturn(List.of(entity));
        when(transactionMapper.toResponse(entity)).thenReturn(expectedResponse);

        List<TransactionResponse> results = transactionService.getTransactionHistory();

        assertThat(results).containsExactly(expectedResponse);
        verify(securityHelper).verifyHistoryAccess("alice");
    }

    @Test
    @DisplayName("getTransactionHistory(username, pageable) — verifies access, clamps pageable, queries and maps")
    void getTransactionHistory_paginated_delegatesCorrectly() {
        Pageable requested = PageRequest.of(0, 500); // Exceeds MAX_PAGE_SIZE (100)
        Transaction entity = new Transaction();
        Page<Transaction> page = new PageImpl<>(List.of(entity));

        when(transactionRepository.findAllByUser(eq("alice"), any(Pageable.class))).thenReturn(page);
        when(transactionMapper.toResponse(entity)).thenReturn(expectedResponse);

        Page<TransactionResponse> responsePage = transactionService.getTransactionHistory("alice", requested);

        assertThat(responsePage.getContent()).containsExactly(expectedResponse);
        verify(securityHelper).verifyHistoryAccess("alice");
        verify(transactionRepository).findAllByUser(eq("alice"), any(Pageable.class));
    }

    @Test
    @DisplayName("getLastTransaction — returns latest mapped transaction response")
    void getLastTransaction_found_returnsMappedResponse() {
        Transaction entity = new Transaction();
        when(transactionRepository.findLatestByUser("alice")).thenReturn(Optional.of(entity));
        when(transactionMapper.toResponse(entity)).thenReturn(expectedResponse);

        TransactionResponse actual = transactionService.getLastTransaction("alice");

        assertThat(actual).isEqualTo(expectedResponse);
        verify(securityHelper).verifyHistoryAccess("alice");
    }

    @Test
    @DisplayName("getLastTransaction — throws EntityNotFoundException when user has no transactions")
    void getLastTransaction_notFound_throwsEntityNotFoundException() {
        when(transactionRepository.findLatestByUser("alice")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.getLastTransaction("alice"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("No transactions found for user: alice");

        verify(securityHelper).verifyHistoryAccess("alice");
    }

    @Test
    @DisplayName("toTransactionResponse — backwards-compatible delegate routes to TransactionMapper")
    void toTransactionResponse_delegatesToMapper() {
        Transaction entity = new Transaction();
        when(transactionMapper.toResponse(entity)).thenReturn(expectedResponse);

        TransactionResponse actual = transactionService.toTransactionResponse(entity);

        assertThat(actual).isEqualTo(expectedResponse);
        verify(transactionMapper).toResponse(entity);
    }
}
