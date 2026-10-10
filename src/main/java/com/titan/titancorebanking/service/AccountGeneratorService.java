package com.titan.titancorebanking.service;

import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.User;
import com.titan.titancorebanking.enums.AccountType;
import com.titan.titancorebanking.enums.Currency;
import com.titan.titancorebanking.enums.AccountStatus;
import com.titan.titancorebanking.exception.AccountGenerationException;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.utils.AccountNumberUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountGeneratorService {

    private final AccountRepository accountRepository;
    private static final int MAX_GENERATION_ATTEMPTS = 5;

    // REQUIRES_NEW prevents UnexpectedRollbackException in the parent transaction upon DB collision
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Account generateAndPersistAccount(User user, AccountType type, Currency currency) {
        int attempts = 0;
        while (attempts < MAX_GENERATION_ATTEMPTS) {
            try {
                Account account = Account.builder()
                        .accountNumber(AccountNumberUtils.generateAccountNumber())
                        .accountType(type != null ? type : AccountType.SAVINGS)
                        .balance(BigDecimal.ZERO)
                        .user(user)
                        .createdAt(LocalDateTime.now())
                        .status(AccountStatus.ACTIVE)
                        .currency(currency != null ? currency : Currency.USD)
                        .overdraftLimit(BigDecimal.ZERO)
                        .build();

                return accountRepository.saveAndFlush(account); // saveAndFlush forces constraint evaluation immediately

            } catch (DataIntegrityViolationException e) {
                log.warn("Account number collision detected. Retrying... Attempt: {}", attempts + 1);
                attempts++;
            }
        }
        throw new AccountGenerationException("System Busy: Could not generate unique account number.");
    }
}