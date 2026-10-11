package com.titan.titancorebanking.graphql;

import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.model.User;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.TransactionRepository;
import com.titan.titancorebanking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class CoreBankingGraphQLController {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionEventPublisher eventPublisher;

    @QueryMapping
    @PreAuthorize("hasRole('ADMIN') or @accountSecurity.isAccountOwner(authentication, #id)")
    public User user(@Argument Long id) {
        return userRepository.findById(id).orElse(null);
    }

    @QueryMapping
    @PreAuthorize("hasRole('ADMIN') or @accountSecurity.isAccountOwner(authentication, #id)")
    public Account account(@Argument Long id) {
        return accountRepository.findById(id).orElse(null);
    }

    @QueryMapping
    @PreAuthorize("hasRole('ADMIN') or @accountSecurity.isAccountOwner(authentication, #accountId)")
    public List<Transaction> recentTransactions(@Argument Long accountId, @Argument Integer limit) {
        int cap = (limit != null && limit > 0 && limit <= 50) ? limit : 10;
        return transactionRepository.findTop10ByFromAccountIdOrToAccountIdOrderByTimestampDesc(accountId, accountId)
                .stream().limit(cap).toList();
    }

    @SchemaMapping(typeName = "User", field = "accounts")
    public List<Account> accounts(User user) {
        return accountRepository.findByUserId(user.getId());
    }

    @SchemaMapping(typeName = "User", field = "maskedAccountNumber")
    public String maskedAccountNumber(User user) {
        List<Account> accounts = accountRepository.findByUserId(user.getId());
        if (accounts.isEmpty()) return null;
        String num = accounts.getFirst().getAccountNumber();
        return "****" + num.substring(Math.max(0, num.length() - 4));
    }

    @SchemaMapping(typeName = "User", field = "totalLedgerBalance")
    @PreAuthorize("hasRole('ADMIN')")
    public String totalLedgerBalance(User user) {
        return accountRepository.findByUserId(user.getId()).stream()
                .map(Account::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .toPlainString();
    }

    @SchemaMapping(typeName = "Account", field = "balance")
    @PreAuthorize("hasRole('ADMIN') or @accountSecurity.isAccountOwner(authentication, #account.id)")
    public String balance(Account account) {
        return account.getBalance().toPlainString();
    }

    @SubscriptionMapping
    @PreAuthorize("hasRole('ADMIN') or @accountSecurity.isAccountOwner(authentication, #accountId)")
    public Flux<TransactionEventDTO> transactionCompleted(@Argument Long accountId) {
        return eventPublisher.getStream()
                .filter(e -> e.accountId().equals(accountId));
    }
}