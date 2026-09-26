package com.titan.titancorebanking.security;

import com.titan.titancorebanking.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component("accountSecurity")
@RequiredArgsConstructor
@Slf4j
public class AccountSecurity {

    private final AccountRepository accountRepository;

    /**
     * Verifies whether the authenticated principal is the rightful owner of the account ID.
     */
    public boolean isAccountOwner(Authentication authentication, Long accountId) {
        if (authentication == null || !authentication.isAuthenticated() || accountId == null) {
            return false;
        }

        String currentUsername = authentication.getName();
        return accountRepository.findById(accountId)
                .map(account -> account.getUser() != null && currentUsername.equals(account.getUser().getUsername()))
                .orElse(false);
    }
}
