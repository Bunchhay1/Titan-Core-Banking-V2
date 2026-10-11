package com.titan.titancorebanking.integration;

import com.titan.riskengine.RiskCheckResponse;
import com.titan.titancorebanking.dto.request.TransactionRequest;
import com.titan.titancorebanking.enums.AccountStatus;
import com.titan.titancorebanking.enums.AccountType;
import com.titan.titancorebanking.enums.Currency;
import com.titan.titancorebanking.enums.TransactionStatus;
import com.titan.titancorebanking.enums.TransactionType;
import com.titan.titancorebanking.enums.UserTier;
import com.titan.titancorebanking.exception.DailyLimitExceededException;
import com.titan.titancorebanking.exception.InsufficientBalanceException;
import com.titan.titancorebanking.exception.InvalidPinException;
import com.titan.titancorebanking.failsafe.DeadMansSwitchService;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.model.User;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.TransactionRepository;
import com.titan.titancorebanking.repository.UserRepository;
import com.titan.titancorebanking.service.AccountBucketService;
import com.titan.titancorebanking.service.RiskEngineGrpcService;
import com.titan.titancorebanking.service.TransactionService;
import com.titan.titancorebanking.service.fee.FeeStrategyFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Production-grade Testcontainers integration test suite for {@link TransactionService}.
 *
 * <h3>Infrastructure</h3>
 * <ul>
 *   <li>Real PostgreSQL 16 via Testcontainers — exercises actual ACID, locks, and constraint behavior.</li>
 *   <li>Real Redis 7.2 via Testcontainers — validates idempotency cache pipeline end-to-end.</li>
 *   <li>Flyway migrations run automatically on context startup, applying the full schema.</li>
 * </ul>
 *
 * <h3>External Dependencies Mocked</h3>
 * <ul>
 *   <li>{@link RiskEngineGrpcService} — gRPC AI Risk Engine (external network service).</li>
 *   <li>{@link DeadMansSwitchService} — depends on Redis keys that vary by environment.</li>
 *   <li>{@link AccountBucketService} — bucket partitioning is an internal scalability concern;
 *       tests focus on balance semantics. The mock always credits the receiver directly.</li>
 * </ul>
 *
 * <h3>Test Isolation Strategy</h3>
 * Each test starts with a clean slate via {@link #cleanDatabase()} in {@link BeforeEach}.
 * Table truncation is used instead of {@code @Transactional} rollback because:
 * <ol>
 *   <li>Concurrency tests ({@code transfer_deadlockPrevention_concurrentBidirectional}) spawn
 *       multiple threads; a test-level transaction would not wrap them.</li>
 *   <li>The service uses {@code REQUIRES_NEW} in {@link com.titan.titancorebanking.service.TransactionAuditService},
 *       which bypasses test-level transaction rollback anyway.</li>
 * </ol>
 *
 * <h3>SecurityContext Strategy</h3>
 * Direct service calls do not go through the JWT filter chain, so the
 * {@code SecurityContextHolder} is empty by default. This matters for any component
 * that reads the authenticated principal (e.g. cache-key building). {@link BeforeEach}
 * sets up Alice's authentication as the default; individual tests override it when
 * they need a different caller (Bob, Charlie). {@link AfterEach} always clears the
 * context to prevent cross-test contamination.
 */
@SpringBootTest
@Testcontainers
class TransactionServiceIntegrationTest {

    // ── Testcontainers — shared across all tests for speed ───────────────────

    /**
     * PostgreSQL 16 container — matches the production database version exactly.
     * Shared across the test class to avoid per-test container startup overhead.
     */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("titandb_test")
            .withUsername("titan_test")
            .withPassword("titan_test_secret");

    /**
     * Redis 7.2 container — required by {@link com.titan.titancorebanking.service.IdempotencyService}
     * and {@link com.titan.titancorebanking.service.OutboxRelayService}.
     * The port is mapped dynamically to avoid conflicts with a running local Redis.
     */
    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379);

    /**
     * Wires container connection details into Spring's environment before the
     * application context is created. This runs before any bean is instantiated,
     * so Flyway picks up the correct JDBC URL automatically.
     */
    @DynamicPropertySource
    static void overrideConnectionProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());

        // Disable Kafka for tests — the OutboxRelayService will use the AtomicBoolean fallback path.
        registry.add("kafka.enabled", () -> "false");
        // Disable the Dead Man's Switch heartbeat scheduler to avoid Redis-key side effects.
        registry.add("dms.enabled", () -> "false");
        // Use a stable 256-bit JWT secret for test-context determinism.
        registry.add("app.jwt.secret",
                () -> "74657374536563726574466F72546974616E42616E6B696E6754657374733231");
        // Disable gRPC auto-configuration — we mock the service bean directly.
        registry.add("grpc.client.riskEngineClient.address", () -> "static://localhost:50099");
        registry.add("titan.ai.host", () -> "localhost");
        registry.add("titan.ai.port",  () -> "50099");
    }

    // ── Spring Beans ─────────────────────────────────────────────────────────

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Injected to derive the expected fee from the real strategy rather than hardcoding
     * a constant, so the test doesn't silently pass with the wrong expected value if the
     * fee tier changes in the future.
     */
    @Autowired
    private FeeStrategyFactory feeStrategyFactory;

    // ── External Dependencies Mocked ─────────────────────────────────────────

    /** Mocked: external gRPC AI Risk Engine — not under test. */
    @MockBean
    private RiskEngineGrpcService riskEngineGrpcService;

    /**
     * Mocked: Dead Man's Switch — uses Redis keys that are environment-specific.
     * Stubbed to return {@code false} (no lockdown) in {@link #cleanDatabase()}.
     */
    @MockBean
    private DeadMansSwitchService deadMansSwitchService;

    /**
     * Mocked: AccountBucketService — bucket partitioning is a scalability concern outside
     * the scope of TransactionService correctness tests. The doAnswer mock applied in
     * {@link #cleanDatabase()} credits the receiver's parent-row balance directly,
     * which is synchronized to prevent lost updates during the concurrency test.
     */
    @MockBean
    private AccountBucketService accountBucketService;

    // ── Test Fixtures ─────────────────────────────────────────────────────────

    /** Plaintext PIN used for all test accounts — hashed before persistence. */
    private static final String PLAINTEXT_PIN  = "1234";
    /** Wrong PIN used in negative PIN validation tests. */
    private static final String WRONG_PIN      = "9999";
    /** Daily transfer limit from application config (must match titan.transfer.daily-limit). */
    private static final BigDecimal DAILY_LIMIT = new BigDecimal("50000");

    // Test actors — recreated clean before each test.
    private User alice;
    private User bob;
    private User charlie;
    private Account aliceAccount;
    private Account bobAccount;
    private Account charlieAccount;

    // ── Test Lifecycle ────────────────────────────────────────────────────────

    /**
     * Resets the database state and the Spring SecurityContext before every test.
     *
     * <h4>Table truncation order</h4>
     * Child tables are truncated before parent tables to respect FK constraints.
     * RESTART IDENTITY resets SERIAL sequences so IDs are predictable across test runs.
     * CASCADE removes dependent rows in tables not explicitly listed.
     *
     * <h4>SecurityContext</h4>
     * A fresh {@link UsernamePasswordAuthenticationToken} for Alice is installed into
     * {@link SecurityContextHolder} after user objects are created. Tests that use a
     * different caller (Bob, Charlie) override this within the test method itself.
     */
    @BeforeEach
    void cleanDatabase() {
        // Truncate in dependency order (children before parents).
        // CASCADE handles any FK-referenced rows automatically.
        // account_buckets is Hibernate-managed (ddl-auto=update) — the IF EXISTS guard
        // handles a fresh container where Hibernate DDL hasn't fired yet.
        jdbcTemplate.execute(
                "DO $$ BEGIN " +
                "  TRUNCATE TABLE outbox_events, idempotency_keys, ledger_entry, " +
                "    audit_logs, transactions, accounts, users RESTART IDENTITY CASCADE; " +
                "  IF EXISTS (SELECT FROM information_schema.tables " +
                "             WHERE table_schema = 'public' AND table_name = 'account_buckets') THEN " +
                "    TRUNCATE TABLE account_buckets RESTART IDENTITY CASCADE; " +
                "  END IF; " +
                "END $$");

        // Seed test users — PINs and passwords are bcrypt-hashed exactly as production does it.
        String hashedPassword = passwordEncoder.encode("Password123!");
        String hashedPin      = passwordEncoder.encode(PLAINTEXT_PIN);

        alice = userRepository.save(User.builder()
                .firstName("Alice").lastName("Titan")
                .username("alice_test").email("alice@titan.test")
                .password(hashedPassword).pin(hashedPin)
                .role("ROLE_USER").tier(UserTier.STANDARD)
                .accountNonLocked(true)
                .build());

        bob = userRepository.save(User.builder()
                .firstName("Bob").lastName("Titan")
                .username("bob_test").email("bob@titan.test")
                .password(hashedPassword).pin(hashedPin)
                .role("ROLE_USER").tier(UserTier.STANDARD)
                .accountNonLocked(true)
                .build());

        charlie = userRepository.save(User.builder()
                .firstName("Charlie").lastName("Titan")
                .username("charlie_test").email("charlie@titan.test")
                .password(hashedPassword).pin(hashedPin)
                .role("ROLE_USER").tier(UserTier.STANDARD)
                .accountNonLocked(true)
                .build());

        aliceAccount = accountRepository.save(Account.builder()
                .accountNumber("ACC-ALICE-001")
                .accountType(AccountType.SAVINGS)
                .currency(Currency.USD)
                .balance(new BigDecimal("10000.0000"))
                .overdraftLimit(BigDecimal.ZERO)
                .status(AccountStatus.ACTIVE)
                .user(alice)
                .build());

        bobAccount = accountRepository.save(Account.builder()
                .accountNumber("ACC-BOB-001")
                .accountType(AccountType.SAVINGS)
                .currency(Currency.USD)
                .balance(new BigDecimal("5000.0000"))
                .overdraftLimit(BigDecimal.ZERO)
                .status(AccountStatus.ACTIVE)
                .user(bob)
                .build());

        charlieAccount = accountRepository.save(Account.builder()
                .accountNumber("ACC-CHARLIE-001")
                .accountType(AccountType.SAVINGS)
                .currency(Currency.USD)
                .balance(new BigDecimal("2000.0000"))
                .overdraftLimit(BigDecimal.ZERO)
                .status(AccountStatus.ACTIVE)
                .user(charlie)
                .build());

        // ── SecurityContext: default caller is Alice ──────────────────────────
        // Direct service calls bypass the JWT filter, so the SecurityContext would be
        // empty without this setup. Components reading the authenticated principal
        // (e.g. IdempotencyService cache-key building) need a populated context.
        setSecurityContext(alice);

        // ── Mock stubs ────────────────────────────────────────────────────────

        // Risk Engine always returns ALLOW for test amounts.
        RiskCheckResponse allowResponse = RiskCheckResponse.newBuilder()
                .setRiskScore(10)
                .setRiskLevel("LOW")
                .setAction("ALLOW")
                .build();
        when(riskEngineGrpcService.analyzeTransaction(anyString(), anyDouble()))
                .thenReturn(allowResponse);

        // Dead Man's Switch never trips during tests.
        when(deadMansSwitchService.isLockdownActive()).thenReturn(false);

        // AccountBucketService mock: creditBucket applies the credit directly to the
        // parent account balance row so balance assertions in tests reflect the correct
        // totals without needing the real bucket infrastructure.
        //
        // CONCURRENCY SAFETY: synchronized on the class lock to prevent lost updates
        // under the 50-thread concurrency test. This is test-only synchronization;
        // the real implementation uses PostgreSQL row locks instead.
        when(accountBucketService.selectRandomBucketIndex()).thenReturn(0);
        doAnswer(invocation -> {
            Long parentAccountId = invocation.getArgument(0);
            BigDecimal amount    = invocation.getArgument(2);
            synchronized (TransactionServiceIntegrationTest.class) {
                accountRepository.findById(parentAccountId).ifPresent(account -> {
                    account.setBalance(account.getBalance().add(amount));
                    accountRepository.save(account);
                });
            }
            return null;
        }).when(accountBucketService).creditBucket(anyLong(), anyInt(), any(BigDecimal.class));
    }

    /**
     * Clears the Spring SecurityContext after each test to prevent cross-test contamination.
     * This is especially important in a shared-context test class (all tests share the same
     * Spring application context), where a leftover authentication from one test could
     * affect the next.
     */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ════════════════════════════════════════════════════════════════════════
    // TEST CASES
    // ════════════════════════════════════════════════════════════════════════

    // ── 1. Transfer — Happy Path ─────────────────────────────────────────────

    /**
     * Invariant: A successful transfer debits the sender by (amount + fee),
     * credits the receiver by the exact transfer amount, and records a SUCCESS
     * transaction with type=TRANSFER.
     *
     * <p>Fee is derived from the real {@link FeeStrategyFactory} for STANDARD tier.
     * Alice initial balance: $10,000. Transfer: $100. Expected fee is calculated
     * dynamically so the test won't silently pass with a stale rate if the
     * strategy changes.
     */
    @Test
    @DisplayName("transfer_happyPath — sender debited (amount+fee), receiver credited, status=SUCCESS")
    void transfer_happyPath() {
        // SecurityContext: Alice (default from @BeforeEach)
        BigDecimal transferAmount = new BigDecimal("100.00");
        // Derive fee from the real strategy — avoids hardcoded constant drift.
        BigDecimal expectedFee    = feeStrategyFactory
                .getStrategy(UserTier.STANDARD)
                .calculateFee(transferAmount);
        BigDecimal totalDeduction = transferAmount.add(expectedFee);

        BigDecimal aliceInitial = aliceAccount.getBalance();
        BigDecimal bobInitial   = bobAccount.getBalance();

        TransactionRequest request = new TransactionRequest(
                aliceAccount.getAccountNumber(),
                bobAccount.getAccountNumber(),
                transferAmount,
                PLAINTEXT_PIN,
                "Integration test transfer",
                null, null, null
        );

        Transaction result = transactionService.transfer(request, alice.getUsername());

        // Assert transaction record
        assertThat(result).isNotNull();
        assertThat(result.getId()).isPositive();
        assertThat(result.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(result.getTransactionType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(result.getAmount()).isEqualByComparingTo(transferAmount);
        assertThat(result.getTransactionReference()).startsWith("TX-");

        // Assert sender balance: reduced by amount + fee
        Account refreshedAlice = accountRepository.findByAccountNumber(aliceAccount.getAccountNumber()).orElseThrow();
        assertThat(refreshedAlice.getBalance())
                .as("Alice's balance should decrease by amount (%s) + fee (%s)", transferAmount, expectedFee)
                .isEqualByComparingTo(aliceInitial.subtract(totalDeduction));

        // Assert receiver balance: increased by exactly the transfer amount (no fee on receiver)
        Account refreshedBob = accountRepository.findByAccountNumber(bobAccount.getAccountNumber()).orElseThrow();
        assertThat(refreshedBob.getBalance())
                .as("Bob's balance should increase by the transfer amount only (no fee charged to receiver)")
                .isEqualByComparingTo(bobInitial.add(transferAmount));

        // Assert exactly one transaction was persisted
        assertThat(transactionRepository.findById(result.getId())).isPresent();
    }

    // ── 2. Deadlock Prevention — Concurrent Bidirectional Transfers ──────────

    /**
     * Invariant: Concurrent bidirectional transfers between Alice and Bob must never
     * produce a PostgreSQL deadlock (SQLSTATE 40P01). Deterministic lock ordering
     * (lower account ID first) makes circular waits structurally impossible.
     *
     * <p>Protocol:
     * <ul>
     *   <li>25 threads transfer Alice → Bob simultaneously.</li>
     *   <li>25 threads transfer Bob → Alice simultaneously.</li>
     *   <li>Each transfer is for $1.00 to keep balances above zero throughout.</li>
     * </ul>
     *
     * <p>Expected outcome: All 50 operations complete as either SUCCESS or an accepted
     * business rejection (InsufficientBalance, DailyLimit). Zero "40P01" deadlock errors.
     * Final combined balance (Alice + Bob) equals (initialAlice + initialBob) minus
     * (successCount × feePerTransfer).
     *
     * <p>Note: The {@link AccountBucketService} mock's doAnswer is synchronized on the
     * class lock to prevent lost updates in the mock's in-memory operation. This is
     * test-infrastructure synchronization only; the production code uses PostgreSQL
     * row locks for this guarantee.
     */
    @Test
    @DisplayName("transfer_deadlockPrevention_concurrentBidirectional — ZERO deadlocks under 50 concurrent threads")
    @Timeout(value = 60, unit = TimeUnit.SECONDS) // Fail fast if a real deadlock causes indefinite blocking
    void transfer_deadlockPrevention_concurrentBidirectional() throws InterruptedException {
        final int THREADS_PER_DIRECTION = 25;
        final BigDecimal TRANSFER_AMOUNT = new BigDecimal("1.00");

        // Fee for one $1.00 STANDARD-tier transfer
        final BigDecimal feePerTransfer = feeStrategyFactory
                .getStrategy(UserTier.STANDARD)
                .calculateFee(TRANSFER_AMOUNT);

        // Give both accounts generous balances so insufficient-balance rejections
        // don't dominate — the goal is to exercise the lock ordering code path.
        final BigDecimal aliceInitialBalance = new BigDecimal("100000.0000");
        final BigDecimal bobInitialBalance   = new BigDecimal("100000.0000");
        aliceAccount.setBalance(aliceInitialBalance);
        bobAccount.setBalance(bobInitialBalance);
        accountRepository.save(aliceAccount);
        accountRepository.save(bobAccount);

        ExecutorService executor = Executors.newFixedThreadPool(THREADS_PER_DIRECTION * 2);
        List<Future<TransactionOutcome>> futures = new ArrayList<>();

        // Submit Alice→Bob transfers
        for (int i = 0; i < THREADS_PER_DIRECTION; i++) {
            final int seq = i;
            futures.add(executor.submit(() -> attemptTransfer(
                    aliceAccount.getAccountNumber(),
                    bobAccount.getAccountNumber(),
                    alice.getUsername(),
                    TRANSFER_AMOUNT,
                    "A->B concurrent #" + seq)));
        }

        // Submit Bob→Alice transfers (the bidirectional pattern that would cause deadlocks
        // without deterministic lock ordering)
        for (int i = 0; i < THREADS_PER_DIRECTION; i++) {
            final int seq = i;
            futures.add(executor.submit(() -> attemptTransfer(
                    bobAccount.getAccountNumber(),
                    aliceAccount.getAccountNumber(),
                    bob.getUsername(),
                    TRANSFER_AMOUNT,
                    "B->A concurrent #" + seq)));
        }

        executor.shutdown();
        boolean terminated = executor.awaitTermination(55, TimeUnit.SECONDS);
        assertThat(terminated)
                .as("ExecutorService must terminate within 55 s — if it did not, a real deadlock may have occurred")
                .isTrue();

        // Collect results
        int successCount        = 0;
        int businessRejectCount = 0;
        List<String> deadlockErrors = new CopyOnWriteArrayList<>();

        for (Future<TransactionOutcome> future : futures) {
            TransactionOutcome outcome;
            try {
                outcome = future.get(1, TimeUnit.SECONDS);
            } catch (Exception e) {
                String causeMsg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                if (causeMsg != null && (causeMsg.contains("40P01") || causeMsg.toLowerCase().contains("deadlock"))) {
                    deadlockErrors.add(causeMsg);
                } else {
                    businessRejectCount++;
                }
                continue;
            }

            if (outcome.deadlockError() != null) {
                deadlockErrors.add(outcome.deadlockError());
            } else if (outcome.isSuccess()) {
                successCount++;
            } else {
                businessRejectCount++;
            }
        }

        // ── Core invariant: ZERO PostgreSQL deadlocks ─────────────────────────
        assertThat(deadlockErrors)
                .as("Deterministic lock ordering must eliminate all PostgreSQL deadlocks (SQLSTATE 40P01). Found: %s",
                        deadlockErrors)
                .isEmpty();

        // ── Balance reconciliation ────────────────────────────────────────────
        // Each successful transfer deducts one fee from the combined balance pool.
        // The combined (Alice + Bob) balance must equal initial total minus total fees paid.
        Account finalAlice = accountRepository.findByAccountNumber(aliceAccount.getAccountNumber()).orElseThrow();
        Account finalBob   = accountRepository.findByAccountNumber(bobAccount.getAccountNumber()).orElseThrow();

        BigDecimal totalFeesPaid           = feePerTransfer.multiply(BigDecimal.valueOf(successCount));
        BigDecimal expectedCombinedBalance = aliceInitialBalance.add(bobInitialBalance).subtract(totalFeesPaid);
        BigDecimal actualCombinedBalance   = finalAlice.getBalance().add(finalBob.getBalance());

        assertThat(actualCombinedBalance)
                .as("Combined (Alice + Bob) balance must equal initial total (%s) minus fees paid (%s × %d = %s). " +
                    "Actual: %s",
                    aliceInitialBalance.add(bobInitialBalance), feePerTransfer, successCount,
                    totalFeesPaid, actualCombinedBalance)
                .isEqualByComparingTo(expectedCombinedBalance);
    }

    // ── 3. Idempotency — Same Key Submitted Twice ────────────────────────────

    /**
     * Invariant: Submitting the same transfer twice with an identical idempotency key
     * returns the same Transaction ID on the second call. Exactly ONE transaction
     * record exists in the database for that key — no double-charge.
     *
     * <p>The first call primes both the Redis cache and the DB column. The second call
     * hits the Redis cache and returns the original result without re-executing the
     * money movement. Alice's balance must not change after the second call.
     *
     * <p>The DB uniqueness assertion uses a raw JDBC count query (not
     * {@code findByIdempotencyKey}) to prove exactly one row — an
     * {@code Optional} only proves "at least one or zero".
     */
    @Test
    @DisplayName("transfer_idempotency_sameKeyTwice — second call returns same txId, DB has exactly ONE record")
    void transfer_idempotency_sameKeyTwice() {
        // SecurityContext: Alice (default from @BeforeEach)
        String idempotencyKey = "idem-" + UUID.randomUUID();

        TransactionRequest firstRequest = new TransactionRequest(
                aliceAccount.getAccountNumber(),
                bobAccount.getAccountNumber(),
                new BigDecimal("200.00"),
                PLAINTEXT_PIN,
                "Idempotency test",
                null, null, idempotencyKey
        );

        // First submission — should execute normally
        Transaction firstResult = transactionService.transfer(firstRequest, alice.getUsername());
        assertThat(firstResult).isNotNull();
        assertThat(firstResult.getStatus()).isEqualTo(TransactionStatus.SUCCESS);

        BigDecimal aliceBalanceAfterFirst =
                accountRepository.findByAccountNumber(aliceAccount.getAccountNumber()).orElseThrow().getBalance();

        // Second submission — identical key, must be a no-op
        Transaction secondResult = transactionService.transfer(firstRequest, alice.getUsername());

        // Same Transaction ID — proof no new record was created
        assertThat(secondResult.getId())
                .as("Idempotency: second call must return the SAME transaction ID as the first call")
                .isEqualTo(firstResult.getId());

        // Balance must not have changed after the deduplicated (no-op) call
        BigDecimal aliceBalanceAfterSecond =
                accountRepository.findByAccountNumber(aliceAccount.getAccountNumber()).orElseThrow().getBalance();
        assertThat(aliceBalanceAfterSecond)
                .as("Balance must not change after a deduplicated idempotent request")
                .isEqualByComparingTo(aliceBalanceAfterFirst);

        // Exactly ONE transaction record for this idempotency key — JDBC count is definitive.
        Long countWithKey = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE idempotency_key = ?",
                Long.class, idempotencyKey);
        assertThat(countWithKey)
                .as("Database must contain exactly ONE transaction for idempotency key '%s'", idempotencyKey)
                .isEqualTo(1L);
    }

    // ── 4. Transfer — Insufficient Balance ──────────────────────────────────

    /**
     * Invariant: Attempting to transfer more than (balance + overdraftLimit) must
     * throw {@link InsufficientBalanceException} and leave both accounts' balances
     * completely unchanged — no partial debit.
     *
     * <p>Alice balance: $10,000. Overdraft: $0. Transfer request: $15,000.
     * Total required: $15,000 + fee. Both exceed $10,000. Must be rejected.
     */
    @Test
    @DisplayName("transfer_insufficientBalance — throws InsufficientBalanceException, balances unchanged")
    void transfer_insufficientBalance() {
        // SecurityContext: Alice (default from @BeforeEach)
        BigDecimal aliceBalanceBefore = aliceAccount.getBalance();
        BigDecimal bobBalanceBefore   = bobAccount.getBalance();

        TransactionRequest request = new TransactionRequest(
                aliceAccount.getAccountNumber(),
                bobAccount.getAccountNumber(),
                new BigDecimal("15000.00"), // Far exceeds Alice's $10,000
                PLAINTEXT_PIN,
                "Should be rejected — insufficient balance",
                null, null, null
        );

        assertThatThrownBy(() -> transactionService.transfer(request, alice.getUsername()))
                .as("Transfer exceeding available balance must throw InsufficientBalanceException")
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("Insufficient balance");

        // Verify neither balance changed
        Account refreshedAlice = accountRepository.findByAccountNumber(aliceAccount.getAccountNumber()).orElseThrow();
        Account refreshedBob   = accountRepository.findByAccountNumber(bobAccount.getAccountNumber()).orElseThrow();

        assertThat(refreshedAlice.getBalance())
                .as("Alice's balance must be unchanged after a failed transfer")
                .isEqualByComparingTo(aliceBalanceBefore);
        assertThat(refreshedBob.getBalance())
                .as("Bob's balance must be unchanged after a failed (sender-side) transfer")
                .isEqualByComparingTo(bobBalanceBefore);
    }

    // ── 5. Transfer — IDOR Wrong Owner ───────────────────────────────────────

    /**
     * Invariant: A user must not be able to initiate a transfer from an account they
     * do not own. Attempting to do so must throw {@link SecurityException} — the IDOR
     * (Insecure Direct Object Reference) guard enforced inside the pessimistic lock boundary.
     *
     * <p>Charlie attempts to transfer from Alice's account. The ownership check
     * in {@code executeSecureTransfer} detects the mismatch and throws immediately.
     */
    @Test
    @DisplayName("transfer_idor_wrongOwner — SecurityException thrown when non-owner attempts transfer")
    void transfer_idor_wrongOwner() {
        // Override SecurityContext to Charlie — the IDOR probe caller
        setSecurityContext(charlie);

        BigDecimal aliceBalanceBefore   = aliceAccount.getBalance();
        BigDecimal charlieBalanceBefore = charlieAccount.getBalance();

        // Charlie uses Alice's account number as the source — IDOR probe
        TransactionRequest request = new TransactionRequest(
                aliceAccount.getAccountNumber(),   // Alice's account — Charlie does NOT own this
                charlieAccount.getAccountNumber(),
                new BigDecimal("100.00"),
                PLAINTEXT_PIN,
                "IDOR probe attempt",
                null, null, null
        );

        assertThatThrownBy(() -> transactionService.transfer(request, charlie.getUsername()))
                .as("Transferring from another user's account must throw SecurityException (IDOR guard)")
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("do not own");

        // Neither balance should change
        Account refreshedAlice   = accountRepository.findByAccountNumber(aliceAccount.getAccountNumber()).orElseThrow();
        Account refreshedCharlie = accountRepository.findByAccountNumber(charlieAccount.getAccountNumber()).orElseThrow();

        assertThat(refreshedAlice.getBalance())
                .as("Alice's balance must be unchanged after an IDOR probe targeting her account")
                .isEqualByComparingTo(aliceBalanceBefore);
        assertThat(refreshedCharlie.getBalance())
                .as("Charlie's balance must be unchanged after his own IDOR probe")
                .isEqualByComparingTo(charlieBalanceBefore);
    }

    // ── 6. Transfer — Daily Limit Exceeded ───────────────────────────────────

    /**
     * Invariant: Once a user's total outbound transfers for the current day reach the
     * $50,000 daily limit, any subsequent transfer must throw
     * {@link DailyLimitExceededException}. The check is against persisted SUCCESS
     * records from {@link TransactionRepository#sumOutboundTransfersSince}.
     *
     * <p>Strategy: Insert a $49,500 historical transfer directly via JdbcTemplate to
     * bypass the service layer (which would itself enforce the limit — circular logic).
     * Then attempt a $1,000 transfer: $49,500 + $1,000 = $50,500 > $50,000. Must throw.
     *
     * <p>The column {@code "timestamp"} is a PostgreSQL reserved keyword and is quoted
     * with double quotes in the INSERT statement to avoid a BadSqlGrammarException.
     */
    @Test
    @DisplayName("transfer_dailyLimitExceeded — DailyLimitExceededException thrown after limit is reached")
    void transfer_dailyLimitExceeded() {
        // SecurityContext: Alice (default from @BeforeEach)

        // Give Alice enough balance that the limit, not the balance, is the binding constraint.
        aliceAccount.setBalance(new BigDecimal("200000.0000"));
        accountRepository.save(aliceAccount);

        // Insert a prior SUCCESS transfer of $49,500 directly in the DB to seed the daily total.
        BigDecimal existingDailyTotal = new BigDecimal("49500.00");
        LocalDateTime justAfterMidnight = LocalDate.now().atTime(LocalTime.MIDNIGHT).plusMinutes(1);

        // "timestamp" is a PostgreSQL reserved keyword — quoted to prevent syntax error.
        jdbcTemplate.update(
                "INSERT INTO transactions (transaction_reference, from_account_id, to_account_id, " +
                "amount, transaction_type, status, note, \"timestamp\") VALUES (?,?,?,?,?,?,?,?)",
                "TX-DAILY-LIMIT-SEED-001",
                aliceAccount.getId(),
                bobAccount.getId(),
                existingDailyTotal,
                "TRANSFER",
                "SUCCESS",
                "Seed record for daily limit test",
                justAfterMidnight
        );

        // Verify the seed was applied correctly before the assertion target runs.
        BigDecimal seededSum = transactionRepository.sumOutboundTransfersSince(
                aliceAccount.getId(),
                LocalDate.now().atTime(LocalTime.MIDNIGHT));
        assertThat(seededSum)
                .as("Pre-condition: seeded daily total must be $49,500 before the test transfer")
                .isEqualByComparingTo(existingDailyTotal);

        // Attempt a $1,000 transfer — $49,500 + $1,000 = $50,500 > $50,000 daily limit.
        TransactionRequest request = new TransactionRequest(
                aliceAccount.getAccountNumber(),
                bobAccount.getAccountNumber(),
                new BigDecimal("1000.00"),
                PLAINTEXT_PIN,
                "Should exceed daily limit",
                null, null, null
        );

        assertThatThrownBy(() -> transactionService.transfer(request, alice.getUsername()))
                .as("Transfer pushing daily total over $50,000 must throw DailyLimitExceededException")
                .isInstanceOf(DailyLimitExceededException.class)
                .hasMessageContaining("Daily transfer limit");
    }

    // ── 7. Transfer — Invalid PIN ─────────────────────────────────────────────

    /**
     * Invariant: A transfer request with an incorrect PIN must throw
     * {@link InvalidPinException} and must not debit the sender's account.
     * PIN validation occurs inside the pessimistic lock boundary (TOCTOU safe).
     */
    @Test
    @DisplayName("transfer_invalidPin — InvalidPinException thrown, sender balance unchanged")
    void transfer_invalidPin() {
        // SecurityContext: Alice (default from @BeforeEach)
        BigDecimal aliceBalanceBefore = aliceAccount.getBalance();

        TransactionRequest request = new TransactionRequest(
                aliceAccount.getAccountNumber(),
                bobAccount.getAccountNumber(),
                new BigDecimal("100.00"),
                WRONG_PIN,  // Intentionally wrong PIN
                "Wrong PIN test",
                null, null, null
        );

        assertThatThrownBy(() -> transactionService.transfer(request, alice.getUsername()))
                .as("Wrong PIN must throw InvalidPinException")
                .isInstanceOf(InvalidPinException.class)
                .hasMessageContaining("Invalid PIN");

        // Balance must be completely unchanged
        Account refreshedAlice = accountRepository.findByAccountNumber(aliceAccount.getAccountNumber()).orElseThrow();
        assertThat(refreshedAlice.getBalance())
                .as("Alice's balance must be unchanged after an invalid PIN attempt")
                .isEqualByComparingTo(aliceBalanceBefore);
    }

    // ── 8. Deposit — Happy Path ───────────────────────────────────────────────

    /**
     * Invariant: A deposit must increase the target account's balance by exactly the
     * deposited amount. The resulting transaction must have type=DEPOSIT and status=SUCCESS.
     * No PIN is required for deposits (operator-initiated operation per business rules).
     *
     * <p>Note on {@code fromAccountNumber}: {@link TransactionService#deposit} resolves
     * the target account from {@code toAccountNumber} if non-null, otherwise falls back
     * to {@code fromAccountNumber}. This test uses the fallback path (fromAccountNumber
     * only) which is the standard deposit call pattern from the controller.
     */
    @Test
    @DisplayName("deposit_happyPath — balance increases by deposit amount, type=DEPOSIT, status=SUCCESS")
    void deposit_happyPath() {
        BigDecimal depositAmount = new BigDecimal("500.00");
        BigDecimal balanceBefore = bobAccount.getBalance();

        // deposit() uses toAccountNumber if non-null; falls back to fromAccountNumber.
        // Passing as fromAccountNumber (null toAccountNumber) tests the fallback path
        // that the TransactionController uses for operator-initiated deposits.
        TransactionRequest request = new TransactionRequest(
                bobAccount.getAccountNumber(), // target account via fallback path
                null,
                depositAmount,
                null,  // No PIN required for deposits
                "Integration test deposit",
                null, null, null
        );

        Transaction result = transactionService.deposit(request);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(result.getTransactionType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(result.getAmount()).isEqualByComparingTo(depositAmount);

        Account refreshedBob = accountRepository.findByAccountNumber(bobAccount.getAccountNumber()).orElseThrow();
        assertThat(refreshedBob.getBalance())
                .as("Balance must increase by exactly the deposit amount (no fee on deposits)")
                .isEqualByComparingTo(balanceBefore.add(depositAmount));
    }

    // ── 9. Withdraw — Happy Path ──────────────────────────────────────────────

    /**
     * Invariant: A valid withdrawal must decrease the account balance by exactly the
     * requested amount. The transaction must have type=WITHDRAWAL and status=SUCCESS.
     * PIN verification is required and occurs after the pessimistic lock is acquired
     * (TOCTOU safe — PIN cannot change between check and use).
     */
    @Test
    @DisplayName("withdraw_happyPath — balance decreases by withdrawal amount, type=WITHDRAWAL, status=SUCCESS")
    void withdraw_happyPath() {
        // SecurityContext: Alice (default from @BeforeEach)
        BigDecimal withdrawAmount = new BigDecimal("200.00");
        BigDecimal balanceBefore  = aliceAccount.getBalance();

        TransactionRequest request = new TransactionRequest(
                aliceAccount.getAccountNumber(),
                null,
                withdrawAmount,
                PLAINTEXT_PIN,  // Correct PIN required
                "Integration test withdrawal",
                null, null, null
        );

        Transaction result = transactionService.withdraw(request, alice.getUsername());

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(result.getTransactionType()).isEqualTo(TransactionType.WITHDRAWAL);
        assertThat(result.getAmount()).isEqualByComparingTo(withdrawAmount);

        Account refreshedAlice = accountRepository.findByAccountNumber(aliceAccount.getAccountNumber()).orElseThrow();
        assertThat(refreshedAlice.getBalance())
                .as("Balance must decrease by exactly the withdrawal amount (no fee on withdrawals)")
                .isEqualByComparingTo(balanceBefore.subtract(withdrawAmount));
    }

    // ── 10. Withdraw — Insufficient Balance ──────────────────────────────────

    /**
     * Invariant: Attempting to withdraw more than (balance + overdraftLimit) must
     * throw {@link InsufficientBalanceException} and leave the account balance
     * completely unchanged — no partial debit.
     *
     * <p>Alice balance: $10,000. Overdraft: $0. Withdrawal request: $15,000 > $10,000.
     */
    @Test
    @DisplayName("withdraw_insufficientBalance — InsufficientBalanceException thrown, balance unchanged")
    void withdraw_insufficientBalance() {
        // SecurityContext: Alice (default from @BeforeEach)
        BigDecimal balanceBefore = aliceAccount.getBalance();

        TransactionRequest request = new TransactionRequest(
                aliceAccount.getAccountNumber(),
                null,
                new BigDecimal("15000.00"),  // Exceeds balance
                PLAINTEXT_PIN,
                "Exceeds available balance",
                null, null, null
        );

        assertThatThrownBy(() -> transactionService.withdraw(request, alice.getUsername()))
                .as("Withdrawal exceeding available balance must throw InsufficientBalanceException")
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("Insufficient balance");

        Account refreshedAlice = accountRepository.findByAccountNumber(aliceAccount.getAccountNumber()).orElseThrow();
        assertThat(refreshedAlice.getBalance())
                .as("Balance must be unchanged after a failed withdrawal")
                .isEqualByComparingTo(balanceBefore);
    }

    // ── 11. Transfer — Negative and Zero Amount Rejected ──────────────────────

    @Test
    @DisplayName("transfer_negativeAmount — throws IllegalArgumentException, balance unchanged")
    void transfer_negativeAmount() {
        BigDecimal balanceBefore = aliceAccount.getBalance();

        TransactionRequest request = new TransactionRequest(
                aliceAccount.getAccountNumber(),
                bobAccount.getAccountNumber(),
                new BigDecimal("-50.00"),
                PLAINTEXT_PIN,
                "Negative transfer",
                null, null, null
        );

        assertThatThrownBy(() -> transactionService.transfer(request, alice.getUsername()))
                .as("Negative transfer amount must throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");

        Account refreshedAlice = accountRepository.findByAccountNumber(aliceAccount.getAccountNumber()).orElseThrow();
        assertThat(refreshedAlice.getBalance()).isEqualByComparingTo(balanceBefore);
    }

    // ── 12. Transfer — Excessive Scale Rejected ───────────────────────────────

    @Test
    @DisplayName("transfer_excessiveScale — throws IllegalArgumentException for scale > 4")
    void transfer_excessiveScale() {
        BigDecimal balanceBefore = aliceAccount.getBalance();

        TransactionRequest request = new TransactionRequest(
                aliceAccount.getAccountNumber(),
                bobAccount.getAccountNumber(),
                new BigDecimal("10.12345"), // Scale 5
                PLAINTEXT_PIN,
                "Scale 5 transfer",
                null, null, null
        );

        assertThatThrownBy(() -> transactionService.transfer(request, alice.getUsername()))
                .as("Scale > 4 must throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decimal precision");

        Account refreshedAlice = accountRepository.findByAccountNumber(aliceAccount.getAccountNumber()).orElseThrow();
        assertThat(refreshedAlice.getBalance()).isEqualByComparingTo(balanceBefore);
    }

    // ── 13. Deposit — Negative Amount Rejected ────────────────────────────────

    @Test
    @DisplayName("deposit_negativeAmount — throws IllegalArgumentException, balance unchanged")
    void deposit_negativeAmount() {
        BigDecimal balanceBefore = bobAccount.getBalance();

        TransactionRequest request = new TransactionRequest(
                bobAccount.getAccountNumber(),
                null,
                new BigDecimal("-100.00"),
                null,
                "Negative deposit",
                null, null, null
        );

        assertThatThrownBy(() -> transactionService.deposit(request))
                .as("Negative deposit amount must throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");

        Account refreshedBob = accountRepository.findByAccountNumber(bobAccount.getAccountNumber()).orElseThrow();
        assertThat(refreshedBob.getBalance()).isEqualByComparingTo(balanceBefore);
    }

    // ── 14. Withdraw — Negative Amount Rejected ───────────────────────────────

    @Test
    @DisplayName("withdraw_negativeAmount — throws IllegalArgumentException, balance unchanged")
    void withdraw_negativeAmount() {
        BigDecimal balanceBefore = aliceAccount.getBalance();

        TransactionRequest request = new TransactionRequest(
                aliceAccount.getAccountNumber(),
                null,
                new BigDecimal("-100.00"),
                PLAINTEXT_PIN,
                "Negative withdrawal",
                null, null, null
        );

        assertThatThrownBy(() -> transactionService.withdraw(request, alice.getUsername()))
                .as("Negative withdrawal amount must throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");

        Account refreshedAlice = accountRepository.findByAccountNumber(aliceAccount.getAccountNumber()).orElseThrow();
        assertThat(refreshedAlice.getBalance()).isEqualByComparingTo(balanceBefore);
    }

    // ── 15. Withdraw — IDOR Wrong Owner ──────────────────────────────────────

    @Test
    @DisplayName("withdraw_idor_wrongOwner — SecurityException thrown when non-owner withdraws")
    void withdraw_idor_wrongOwner() {
        setSecurityContext(charlie);

        BigDecimal aliceBalanceBefore = aliceAccount.getBalance();

        TransactionRequest request = new TransactionRequest(
                aliceAccount.getAccountNumber(),
                null,
                new BigDecimal("50.00"),
                PLAINTEXT_PIN,
                "IDOR withdraw attempt",
                null, null, null
        );

        assertThatThrownBy(() -> transactionService.withdraw(request, charlie.getUsername()))
                .as("Withdrawing from another user's account must throw SecurityException")
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("do not own");

        Account refreshedAlice = accountRepository.findByAccountNumber(aliceAccount.getAccountNumber()).orElseThrow();
        assertThat(refreshedAlice.getBalance()).isEqualByComparingTo(aliceBalanceBefore);
    }

    // ── 16. Idempotency Replay Authorization (P1-A) ──────────────────────────

    @Test
    @DisplayName("transfer_idempotency_crossUserReplayBlocked — SecurityException thrown on replay by another user")
    void transfer_idempotency_crossUserReplayBlocked() {
        String idempotencyKey = "idem-cross-" + UUID.randomUUID();

        TransactionRequest request = new TransactionRequest(
                aliceAccount.getAccountNumber(),
                bobAccount.getAccountNumber(),
                new BigDecimal("100.00"),
                PLAINTEXT_PIN,
                "Original transfer",
                null, null, idempotencyKey
        );

        // Alice executes transfer successfully
        Transaction originalTx = transactionService.transfer(request, alice.getUsername());
        assertThat(originalTx.getStatus()).isEqualTo(TransactionStatus.SUCCESS);

        // Charlie tries to replay using the same idempotency key
        setSecurityContext(charlie);

        assertThatThrownBy(() -> transactionService.transfer(request, charlie.getUsername()))
                .as("Replaying another user's idempotency key must throw SecurityException")
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("do not own");
    }

    // ════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Installs a {@link UsernamePasswordAuthenticationToken} for the given user into
     * {@link SecurityContextHolder}. This simulates what the JWT filter would do in
     * production. Must be called before any service method that reads
     * {@code SecurityContextHolder.getContext().getAuthentication()}.
     */
    private void setSecurityContext(User user) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                user.getUsername(), null,
                List.of(new SimpleGrantedAuthority(user.getRole())));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /**
     * Submits a single transfer and captures the outcome (success / business rejection /
     * deadlock error) without letting exceptions escape the thread pool. Used exclusively
     * by the concurrency test to allow clean result collection via {@link Future}.
     *
     * <p>Each thread creates its own SecurityContext before calling the service,
     * because {@link SecurityContextHolder} uses an {@code InheritableThreadLocal}
     * by default but threads from a fixed thread pool are reused — the context must
     * be set explicitly per-submission to ensure the correct principal is in scope.
     *
     * @param from     source account number
     * @param to       destination account number
     * @param username authenticated principal username
     * @param amount   transfer amount
     * @param note     description for log correlation
     * @return a {@link TransactionOutcome} summarising the result
     */
    private TransactionOutcome attemptTransfer(
            String from, String to, String username,
            BigDecimal amount, String note) {
        // Set the SecurityContext for this thread explicitly.
        // Thread pool threads are reused, so they do not inherit the test-thread context.
        userRepository.findByUsername(username).ifPresent(this::setSecurityContext);

        try {
            TransactionRequest req = new TransactionRequest(
                    from, to, amount, PLAINTEXT_PIN, note, null, null, null);
            transactionService.transfer(req, username);
            return TransactionOutcome.successful();
        } catch (InsufficientBalanceException | DailyLimitExceededException e) {
            // Expected business rejections — not system errors.
            return TransactionOutcome.businessRejection(e.getMessage());
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (msg.contains("40P01") || msg.toLowerCase().contains("deadlock")) {
                return TransactionOutcome.deadlock(msg);
            }
            // Traverse the full cause chain — Spring often wraps the JDBC exception.
            Throwable cause = e.getCause();
            while (cause != null) {
                String causeMsg = cause.getMessage();
                if (causeMsg != null && (causeMsg.contains("40P01")
                        || causeMsg.toLowerCase().contains("deadlock"))) {
                    return TransactionOutcome.deadlock(causeMsg);
                }
                cause = cause.getCause();
            }
            return TransactionOutcome.businessRejection(msg);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * Value type capturing the outcome of a single concurrent transfer attempt.
     * Exactly one of the three fields is non-null/non-false at a time.
     */
    private record TransactionOutcome(
            boolean isSuccess,
            String businessRejectionReason,
            String deadlockError
    ) {
        static TransactionOutcome successful() {
            return new TransactionOutcome(true, null, null);
        }

        static TransactionOutcome businessRejection(String reason) {
            return new TransactionOutcome(false, reason, null);
        }

        static TransactionOutcome deadlock(String error) {
            return new TransactionOutcome(false, null, error);
        }
    }
}
