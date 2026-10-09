package com.titan.titancorebanking.security;

import com.titan.titancorebanking.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component("accountSecurity")
@RequiredArgsConstructor
@Slf4j
public class AccountSecurity {

    private final AccountRepository accountRepository;

    // [MODIFIED] Enforced 'final' modifier on parameters to guarantee immutability during security evaluation.
    // ការប្រើប្រាស់ 'final' លើ Parameter ការពារមិនឱ្យមានការកែប្រែតម្លៃដោយចៃដន្យនៅក្នុង Method នេះ ដែលជាស្តង់ដារសុវត្ថិភាពខ្ពស់។
    public boolean isAccountOwner(final Authentication authentication, final Long accountId) {

        // [MODIFIED] Strict, fast-fail null-safety guard clauses to prevent unnecessary database queries.
        // ទប់ស្កាត់តាំងពីដើមទី (Fail-fast) ប្រសិនបើគ្មាន Authentication ឬគ្មាន Account ID ដើម្បីសន្សំសំចៃ Database I/O។
        if (authentication == null || !authentication.isAuthenticated() || accountId == null) {
            log.debug("IDOR check failed: Missing authentication context or accountId.");
            return false;
        }

        final String currentUsername = authentication.getName();

        if (!StringUtils.hasText(currentUsername)) {
            log.warn("IDOR check failed: Authenticated principal has no valid username.");
            return false;
        }

        // [MODIFIED] Added Intrusion Detection Logging and hardened string comparisons.
        // បន្ថែមការកត់ត្រា (Logging) ពេលមានជនខិលខូចព្យាយាមចូលមើលគណនីអ្នកដទៃ (IDOR Attack) ព្រមទាំងការពារបញ្ហា Case-sensitivity លើ Username។
        /*
         * STAFF ENGINEER NOTE:
         * For ultra-high frequency throughput, fetching the entire Account entity via .findById()
         * creates unnecessary Hibernate hydration overhead.
         * Consider adding: boolean existsByIdAndUser_Username(Long id, String username);
         * to your AccountRepository in the future to perform a pure SQL EXISTS check.
         */
        return accountRepository.findById(accountId)
                .map(account -> {
                    boolean isOwner = account.getUser() != null &&
                            StringUtils.hasText(account.getUser().getUsername()) &&
                            currentUsername.equalsIgnoreCase(account.getUser().getUsername().trim());

                    if (!isOwner) {
                        log.warn("SECURITY ALERT (IDOR ATTEMPT): Principal '{}' attempted unauthorized access to account ID '{}'",
                                currentUsername, accountId);
                    }

                    return isOwner;
                })
                .orElse(false);
    }
}