package com.titan.titancorebanking.service.validation;

import com.titan.titancorebanking.constant.TransactionConstants;
import com.titan.titancorebanking.enums.AccountStatus;
import com.titan.titancorebanking.exception.AccountLockedException;
import com.titan.titancorebanking.exception.InvalidPinException;
import com.titan.titancorebanking.model.Account;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class TransactionDomainValidator {

    private final PasswordEncoder passwordEncoder;

    public TransactionDomainValidator(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    public void validateMonetaryAmount(BigDecimal amount, String operation) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(operation + " amount must be greater than zero.");
        }
        if (amount.scale() > TransactionConstants.MAX_MONETARY_SCALE || amount.scale() < 0) {
            throw new IllegalArgumentException(operation + " amount scale exceeds maximum supported decimal precision of " + TransactionConstants.MAX_MONETARY_SCALE + ".");
        }
        if (amount.compareTo(TransactionConstants.MAX_TRANSACTION_AMOUNT) >= 0) {
            throw new IllegalArgumentException(operation + " amount exceeds maximum allowable transaction limit.");
        }
    }

    public void requireActiveAccount(Account account) {
        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new IllegalStateException(String.format("Account %s is closed.", account.getAccountNumber()));
        }
        if (account.getStatus() == AccountStatus.INACTIVE) {
            throw new IllegalStateException(String.format("Account %s is inactive.", account.getAccountNumber()));
        }
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountLockedException(String.format("Account %s is not active. Status: %s",
                    account.getAccountNumber(), account.getStatus()));
        }
    }

    public void validatePin(String rawPin, Account account) {
        if (rawPin == null || rawPin.isBlank()) {
            throw new InvalidPinException("PIN cannot be empty.");
        }
        if (account.getUser() == null || account.getUser().getPin() == null) {
            throw new InvalidPinException("User PIN is not set for account: " + account.getAccountNumber());
        }
        if (!passwordEncoder.matches(rawPin, account.getUser().getPin())) {
            throw new InvalidPinException("Invalid PIN for account: " + account.getAccountNumber());
        }
    }

    public BigDecimal validateAndGetOverdraftLimit(Account account) {
        BigDecimal limit = account.getOverdraftLimit();
        if (limit == null) {
            throw new IllegalStateException("Account overdraft limit cannot be null for account: " + account.getAccountNumber());
        }
        if (limit.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalStateException("Account overdraft limit cannot be negative for account: " + account.getAccountNumber());
        }
        return limit;
    }
}
