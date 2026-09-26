package com.titan.titancorebanking.integration;

import com.titan.titancorebanking.dto.request.TransactionRequest;
import com.titan.titancorebanking.enums.AccountStatus;
import com.titan.titancorebanking.enums.AccountType;
import com.titan.titancorebanking.enums.Currency;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.model.User;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.TransactionRepository;
import com.titan.titancorebanking.repository.UserRepository;
import com.titan.titancorebanking.service.TransactionService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class TransactionIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("titandb")
            .withUsername("postgres")
            .withPassword("password");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void transfer_movesBalancesAndPersistsTransaction() {
        User user = User.builder()
                .username("alice")
                .password(passwordEncoder.encode("secret"))
                .pin(passwordEncoder.encode("1234"))
                .role("ROLE_USER")
                .build();
        user = userRepository.save(user);

        Account fromAccount = Account.builder()
                .accountNumber("111")
                .accountType(AccountType.SAVINGS)
                .currency(Currency.USD)
                .balance(new BigDecimal("20000.00"))
                .status(AccountStatus.ACTIVE)
                .user(user)
                .build();
        Account toAccount = Account.builder()
                .accountNumber("222")
                .accountType(AccountType.SAVINGS)
                .currency(Currency.USD)
                .balance(new BigDecimal("500.00"))
                .status(AccountStatus.ACTIVE)
                .user(user)
                .build();

        accountRepository.save(fromAccount);
        accountRepository.save(toAccount);

        TransactionRequest request = new TransactionRequest(
                "111", "222", new BigDecimal("100.00"), "1234",
                "integration-test", null, "TRANSFER", null, null, null);

        Transaction transaction = transactionService.transfer(request, "alice");

        Account updatedFrom = accountRepository.findByAccountNumber("111").orElseThrow();
        Account updatedTo = accountRepository.findByAccountNumber("222").orElseThrow();

        assertThat(transaction.getId()).isNotNull();
        assertThat(updatedFrom.getBalance()).isEqualByComparingTo("19900.00");
        assertThat(updatedTo.getBalance()).isEqualByComparingTo("600.00");
        assertThat(transactionRepository.count()).isEqualTo(1);
    }
}
