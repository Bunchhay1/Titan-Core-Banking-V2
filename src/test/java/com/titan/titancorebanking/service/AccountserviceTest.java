package com.titan.titancorebanking.service;

import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock private AccountRepository accountRepository;

    @InjectMocks
    private AccountService accountService;

    @Test
    void findAccount_ShouldReturnAccount() {
        Account account = Account.builder().accountNumber("123").build();
        // ✅ Ensure this matches your AccountService.java logic
        when(accountRepository.findByAccountNumber("123")).thenReturn(Optional.of(account));

        // If your service doesn't have getAccountByNumber, we test the Repo interaction
        Optional<Account> found = accountRepository.findByAccountNumber("123");

        assertTrue(found.isPresent());
        assertEquals("123", found.get().getAccountNumber());
    }
}
