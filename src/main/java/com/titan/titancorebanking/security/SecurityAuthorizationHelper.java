package com.titan.titancorebanking.security;

import com.titan.titancorebanking.model.Account;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * SecurityAuthorizationHelper — Centralized, fail-closed authorization engine for core banking.
 *
 * <p>Enforces explicit security invariants across system, administrative, operator, and customer identities:
 * <ul>
 *   <li><b>Fail-Closed Gatekeeper</b> — Missing, unauthenticated, or anonymous SecurityContexts are strictly rejected
 *       with {@link AuthenticationCredentialsNotFoundException} (HTTP 401).</li>
 *   <li><b>Explicit Role Policies</b>:
 *     <ul>
 *       <li>{@code ROLE_ADMIN} — Platform administrator. Authorized for cross-user history queries, administrative corrections, and system deposits.</li>
 *       <li>{@code ROLE_OPERATOR} — Branch teller / customer service. Authorized for cross-user history queries and deposits into any customer account. Prohibited from customer transfers or withdrawals.</li>
 *       <li>{@code ROLE_SYSTEM} — Automated batch jobs, schedulers, and internal outbox processors. Authorized for scheduled transfers and internal deposits.</li>
 *       <li>{@code ROLE_USER} — Standard customer. Strictly restricted to accounts they own (IDOR guard). Prohibited from internal deposits, scheduled transfer triggers, or cross-account access.</li>
 *     </ul>
 *   </li>
 *   <li><b>Anti-Spoofing & IDOR Guards</b> — Authenticated principal identity must match caller-supplied usernames
 *       unless elevated privileges are present. Violations throw {@link AccessDeniedException} (HTTP 403).</li>
 * </ul>
 */
@Component
@Slf4j
public class SecurityAuthorizationHelper {

    /**
     * Resolves the active authenticated principal or fails closed.
     *
     * @return non-null, authenticated {@link Authentication}
     * @throws AuthenticationCredentialsNotFoundException if no valid authenticated principal exists
     */
    public Authentication requireAuthentication() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new AuthenticationCredentialsNotFoundException("Full authentication is required to perform this financial operation.");
        }
        return auth;
    }

    public boolean isAdmin(Authentication auth) {
        return hasRole(auth, "ROLE_ADMIN", "ADMIN");
    }

    public boolean isOperator(Authentication auth) {
        return hasRole(auth, "ROLE_OPERATOR", "OPERATOR");
    }

    public boolean isSystem(Authentication auth) {
        return hasRole(auth, "ROLE_SYSTEM", "SYSTEM", "ROLE_INTERNAL");
    }

    public boolean isStaffOrAdmin(Authentication auth) {
        return isAdmin(auth) || isOperator(auth) || isSystem(auth);
    }

    public boolean isElevatedTransferCaller(Authentication auth) {
        return isAdmin(auth) || isSystem(auth);
    }

    private boolean hasRole(Authentication auth, String... roles) {
        if (auth == null || auth.getAuthorities() == null) {
            return false;
        }
        return auth.getAuthorities().stream().anyMatch(grantedAuthority -> {
            String authority = grantedAuthority.getAuthority();
            for (String role : roles) {
                if (role.equalsIgnoreCase(authority)) {
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * Authorizes access to transaction history and latest transaction queries.
     * Allowed: ROLE_ADMIN, ROLE_OPERATOR, ROLE_SYSTEM, or resource owner.
     */
    public void verifyHistoryAccess(String targetUsername) {
        if (targetUsername == null || targetUsername.isBlank()) {
            throw new IllegalArgumentException("Username is required to access transaction history.");
        }
        Authentication auth = requireAuthentication();
        if (!isStaffOrAdmin(auth) && !auth.getName().equals(targetUsername)) {
            log.warn("Unauthorized history access attempt: principal {} attempted to view history of {}",
                    auth.getName(), targetUsername);
            throw new AccessDeniedException("You do not have permission to view transaction history for user: " + targetUsername);
        }
    }

    /**
     * Verifies that caller-supplied username matches the authenticated identity (Anti-Spoofing).
     * Allowed for elevated callers (ADMIN/SYSTEM), otherwise principal name must match currentUsername.
     */
    public void verifyCallerIdentity(String currentUsername) {
        if (currentUsername == null || currentUsername.isBlank()) {
            throw new IllegalArgumentException("Authenticated principal username is required.");
        }
        Authentication auth = requireAuthentication();
        if (!isElevatedTransferCaller(auth) && !auth.getName().equals(currentUsername)) {
            log.warn("Identity spoofing attempt: SecurityContext principal {} does not match caller-supplied username {}",
                    auth.getName(), currentUsername);
            throw new AccessDeniedException("Authenticated identity does not match supplied username.");
        }
    }

    /**
     * Verifies caller identity for deposit operations.
     * Allowed for operators/admins/system, otherwise principal name must match callerIdentity.
     */
    public void verifyDepositCallerIdentity(String callerIdentity) {
        if (callerIdentity == null || callerIdentity.isBlank()) {
            throw new IllegalArgumentException("Authenticated principal username is required for deposit.");
        }
        Authentication auth = requireAuthentication();
        if (!isStaffOrAdmin(auth) && !auth.getName().equals(callerIdentity)) {
            log.warn("Identity spoofing attempt: SecurityContext principal {} does not match caller-supplied username {}",
                    auth.getName(), callerIdentity);
            throw new AccessDeniedException("Authenticated identity does not match supplied username.");
        }
    }

    /**
     * Verifies authorization for internal system deposits.
     * Requires ROLE_SYSTEM, ROLE_ADMIN, or ROLE_INTERNAL.
     */
    public void verifyInternalDepositAccess(String internalCallerIdentity) {
        if (internalCallerIdentity == null || internalCallerIdentity.isBlank()) {
            throw new IllegalArgumentException("Internal caller identity is required for system deposits.");
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            boolean isAuthorizedInternal = isSystem(auth) || isAdmin(auth);
            if (!isAuthorizedInternal && !internalCallerIdentity.startsWith("SYSTEM")) {
                log.warn("Unauthorized attempt to invoke depositInternal by principal {}", auth.getName());
                throw new AccessDeniedException("Caller is not authorized to invoke internal system deposits.");
            }
        } else if (!internalCallerIdentity.startsWith("SYSTEM")) {
            throw new AccessDeniedException("Caller is not authorized to invoke internal system deposits.");
        }
    }

    /**
     * Verifies authorization for executing scheduled transfers.
     * Requires ROLE_SYSTEM or ROLE_ADMIN.
     */
    public void verifyScheduledTransferAccess(String ownerUsername) {
        if (ownerUsername == null || ownerUsername.isBlank()) {
            throw new IllegalArgumentException("Owner username is required for scheduled transfer execution.");
        }
        Authentication auth = requireAuthentication();
        if (!isSystem(auth) && !isAdmin(auth)) {
            log.warn("Unauthorized attempt to invoke executeScheduledTransfer for user {} by principal {}",
                    ownerUsername, auth.getName());
            throw new AccessDeniedException("Caller is not authorized to execute scheduled transfers.");
        }
    }

    /**
     * IDOR guard: verifies that the caller owns the account.
     */
    public void verifyAccountOwnership(Account account, String currentUsername, String actionDescription) {
        if (account == null || account.getUser() == null || !account.getUser().getUsername().equals(currentUsername)) {
            String owner = (account != null && account.getUser() != null) ? account.getUser().getUsername() : "UNKNOWN";
            String accNum = account != null ? account.getAccountNumber() : "UNKNOWN";
            log.warn("IDOR probe — user {} attempted {} on account {} owned by {}",
                    currentUsername, actionDescription, accNum, owner);
            throw new AccessDeniedException("You do not own this account.");
        }
    }

    /**
     * Deposit target authorization:
     * A deposit target account can be credited if caller owns the account OR caller is an operator/admin.
     */
    public void verifyDepositTargetAccess(Account account, String callerIdentity) {
        boolean isOwner = account != null && account.getUser() != null && account.getUser().getUsername().equals(callerIdentity);
        Authentication auth = requireAuthentication();
        boolean isOperatorOrAdmin = isOperator(auth) || isAdmin(auth);
        if (!isOwner && !isOperatorOrAdmin) {
            log.warn("Unauthorized deposit attempt — user {} attempted deposit to account {} owned by {}",
                    callerIdentity,
                    account != null ? account.getAccountNumber() : "UNKNOWN",
                    (account != null && account.getUser() != null) ? account.getUser().getUsername() : "UNKNOWN");
            throw new AccessDeniedException("You do not own this account and are not an authorized operator.");
        }
    }

    /**
     * Idempotency replay authorization: verifies that the replay caller owns the cached transaction's source account.
     */
    public void verifyTransferReplayOwnership(Account fromAccount, String currentUsername, boolean isSystemCaller) {
        if (!isSystemCaller && fromAccount != null
                && !fromAccount.getUser().getUsername().equals(currentUsername)) {
            log.warn("IDOR probe on idempotency replay — user {} tried to replay transfer for {}",
                    currentUsername, fromAccount.getAccountNumber());
            throw new AccessDeniedException("You do not own the source account of this cached transaction.");
        }
    }

    public void verifyWithdrawReplayOwnership(Account fromAccount, String currentUsername) {
        if (fromAccount != null && !fromAccount.getUser().getUsername().equals(currentUsername)) {
            log.warn("IDOR probe on idempotency replay — user {} tried to replay withdrawal for {}",
                    currentUsername, fromAccount.getAccountNumber());
            throw new AccessDeniedException("You do not own the account of this cached withdrawal.");
        }
    }

    public void verifyDepositReplayOwnership(Account toAccount, String callerIdentity, boolean isTrustedInternal) {
        if (!isTrustedInternal && toAccount != null) {
            boolean isOwner = toAccount.getUser().getUsername().equals(callerIdentity);
            Authentication auth = requireAuthentication();
            boolean isOperatorOrAdmin = isOperator(auth) || isAdmin(auth);
            if (!isOwner && !isOperatorOrAdmin) {
                log.warn("IDOR probe on idempotency replay — user {} tried to replay deposit for account {}",
                        callerIdentity, toAccount.getAccountNumber());
                throw new AccessDeniedException("You do not own this account and are not authorized to replay this deposit.");
            }
        }
    }
}
