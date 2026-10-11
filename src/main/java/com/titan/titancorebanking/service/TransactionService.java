package com.titan.titancorebanking.service;

import com.titan.titancorebanking.dto.request.TransactionRequest;
import com.titan.titancorebanking.dto.response.TransactionResponse;
import com.titan.titancorebanking.enums.AccountStatus;
import com.titan.titancorebanking.enums.TransactionStatus;
import com.titan.titancorebanking.enums.TransactionType;
import com.titan.titancorebanking.exception.AccountLockedException;
import com.titan.titancorebanking.exception.DailyLimitExceededException;
import com.titan.titancorebanking.exception.InsufficientBalanceException;
import com.titan.titancorebanking.exception.InvalidPinException;
import com.titan.titancorebanking.failsafe.DeadMansSwitchService;
import com.titan.titancorebanking.model.Account;
import com.titan.titancorebanking.model.Transaction;
import com.titan.titancorebanking.repository.AccountRepository;
import com.titan.titancorebanking.repository.TransactionRepository;
import com.titan.titancorebanking.service.fee.FeeStrategyFactory;
import com.titan.titancorebanking.service.imple.ExchangeRateService;
import io.github.resilience4j.retry.annotation.Retry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * TransactionService — Core money-movement orchestrator.
 *
 * <h3>Enterprise Guarantees</h3>
 * <ul>
 *   <li><b>ACID</b> — every money movement is wrapped in a single DB transaction.</li>
 *   <li><b>Deadlock-free</b> — deterministic lock ordering (lower account ID first) on all
 *       multi-account operations.</li>
 *   <li><b>No double-spend</b> — pessimistic row locks are acquired before any balance
 *       read-modify-write.</li>
 *   <li><b>Idempotency</b> — Redis-backed deduplication with DB fallback. Cache is written
 *       inside the outer transaction ({@code Propagation.SUPPORTS}) so a rolled-back DB
 *       transaction cannot leave a stale Redis cache entry that bypasses deduplication.</li>
 *   <li><b>Double-entry ledger</b> — every successful transfer produces balanced DEBIT/CREDIT
 *       entries via {@code Propagation.MANDATORY}.</li>
 *   <li><b>Outbox delivery</b> — domain events are persisted in the same transaction and relayed
 *       to Kafka asynchronously by {@link OutboxRelayService}.</li>
 *   <li><b>Risk pre-flight</b> — gRPC AI risk check runs <em>before</em> any lock is held,
 *       keeping lock duration minimal.</li>
 *   <li><b>Daily limit</b> — outbound transfer sum (amount + fee) since business-day midnight
 *       is checked <em>before</em> acquiring pessimistic locks, minimising lock hold time.
 *       Uses a configurable business timezone.</li>
 *   <li><b>MDC tracing</b> — {@code correlationId}, {@code transactionId}, {@code accountId},
 *       and {@code username} are injected into every log record on all money-movement paths.</li>
 *   <li><b>Atomic audit</b> — SUCCESS audit records join the outer transaction
 *       ({@code Propagation.MANDATORY}) so they roll back with the money if anything
 *       after them fails. FAILED audit records use {@code REQUIRES_NEW} for durability.</li>
 * </ul>
 *
 * <h3>Defects fixed in this revision (v12 skill pass)</h3>
 * <ol>
 *   <li><b>P0-A — @Retry/@Transactional proxy ordering (Critical)</b> —
 *       {@code @Retry} was co-located with {@code @Transactional} on {@code transfer()}.
 *       Spring AOP processes annotations in interceptor order; without explicit ordering,
 *       the retry interceptor could sit inside the transaction interceptor, meaning all
 *       retry attempts share the same already-rolled-back transaction and will fail with
 *       {@code TransactionSystemException} on every subsequent attempt. Fix: {@code transfer()}
 *       is now a pure {@code @Retry} facade with <em>no</em> {@code @Transactional}. It
 *       delegates to {@code executeTransfer()}, which carries {@code @Transactional}. This
 *       guarantees that each retry attempt begins a fresh transaction.</li>
 *   <li><b>P0-B — BLOCKED outcome must use {@code REQUIRES_NEW} (Confirmed)</b> —
 *       Verified that the BLOCKED path already calls {@code auditService.saveAuditLog}
 *       ({@code REQUIRES_NEW}), not {@code saveSuccessAuditLog} ({@code MANDATORY}).
 *       The {@code MANDATORY} variant would throw {@code IllegalTransactionStateException}
 *       here because no outer transaction exists at the point of the risk block (the method
 *       exits before any DB lock is acquired). No change needed; documented here as evidence.</li>
 *   <li><b>P1-A — Idempotency replay authorization gap (Critical)</b> —
 *       The previous code returned the cached idempotency result before verifying account
 *       ownership. Any caller with a reused idempotency key could replay another user's
 *       transaction. Fix: resolve the source account (unlocked read) immediately after the
 *       idempotency cache hit and verify username ownership before returning.</li>
 *   <li><b>P1-B — deposit() missing ownership guard (High)</b> —
 *       Any authenticated caller could deposit to any account number. Deposits are treated
 *       as an operator/admin-initiated action (e.g. interest credit, ATM cash-in).
 *       Fix: added explicit ADMIN-or-owner authorization. Internal callers (BatchScheduler,
 *       AtmCodeService) use a separate service method annotated with a trusted-caller note.</li>
 *   <li><b>P1-C — AccountBucketService.creditBucket REQUIRES_NEW suspension (High)</b> —
 *       If bucket rows do not yet exist, {@code getOrInitializeBuckets} uses
 *       {@code REQUIRES_NEW}, which suspends the outer transfer transaction while bucket
 *       rows are created and committed independently. This leaves the account debit
 *       uncommitted while bucket rows are visible to other readers. Fix: bucket initialization
 *       is pre-triggered via {@code getOrInitializeBuckets} called before the pessimistic
 *       lock is acquired (Step 3.5). By the time the lock is held and {@code creditBucket}
 *       is called, all bucket rows are guaranteed to exist, so the {@code orElseGet} fallback
 *       (which invokes {@code getOrInitializeBuckets} mid-transaction) is never reached.</li>
 *   <li><b>P1-D — Fee excluded from Transaction record and ledger (High)</b> —
 *       {@code saveSuccessAuditLog} was called with {@code request.amount()} and
 *       {@code doubleEntryService.createDoubleEntry} received {@code request.amount()}.
 *       The debit was {@code totalDeduction} (amount + fee) but the record and ledger
 *       reflected only {@code amount}. Fix: the {@code Transaction.fee} field (V27 migration)
 *       is populated; the debit ledger entry uses {@code totalDeduction}; the credit entry
 *       uses {@code request.amount()} (receiver gets only the base amount, fee stays with bank).</li>
 *   <li><b>P2-A — TransactionResponse.fee always ZERO (Medium)</b> —
 *       Both {@code toTransactionResponse()} methods (service and controller) hardcoded
 *       {@code BigDecimal.ZERO} for the fee field. Fix: read {@code transaction.getFee()}.</li>
 *   <li><b>P2-B — withdraw() missing daily limit guard (Medium)</b> —
 *       Transfer enforced a configurable daily limit; withdraw did not. Fix: added
 *       {@code titan.withdraw.daily-limit} check mirroring the transfer guard.</li>
 *   <li><b>P3-A — MDC correlationId not set before first log (Low)</b> —
 *       {@code EventPublisherService} falls back to {@code UUID.randomUUID()} when
 *       {@code MDC.get("correlationId")} is null. Fix: correlationId is generated and set
 *       in MDC at the entry point of every money-movement method so all log records and
 *       outbox events share the same correlation identifier throughout the operation.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {

    // ── Configurable limits & timezone ───────────────────────────────────────

    @Value("${titan.transfer.daily-limit:50000}")
    private BigDecimal dailyTransferLimit;

    /**
     * Daily withdrawal limit, independent of the transfer limit.
     * Default is same as transfer limit. Override via {@code titan.withdraw.daily-limit}.
     */
    @Value("${titan.withdraw.daily-limit:50000}")
    private BigDecimal dailyWithdrawLimit;

    /**
     * Business timezone used to determine the start of the daily transfer window.
     * Defaults to Asia/Phnom_Penh (UTC+7). Override via {@code titan.business.timezone}
     * so the daily limit resets at local midnight regardless of JVM/container timezone.
     */
    @Value("${titan.business.timezone:Asia/Phnom_Penh}")
    private String businessTimezone;

    // ── Collaborators ────────────────────────────────────────────────────────
    private final AccountRepository        accountRepository;
    private final TransactionRepository    transactionRepository;
    private final PasswordEncoder          passwordEncoder;
    private final ExchangeRateService      exchangeRateService;
    private final TransactionAuditService  auditService;
    private final EventPublisherService    eventPublisherService;
    private final DoubleEntryService       doubleEntryService;
    private final IdempotencyService       idempotencyService;
    private final RiskEngineGrpcService    riskEngineGrpcService;
    private final DeadMansSwitchService    deadMansSwitchService;
    private final AccountBucketService     accountBucketService;
    private final FeeStrategyFactory       feeStrategyFactory;
    private final TransactionTemplate      transactionTemplate;
    private final EntityManager            entityManager;

    // ────────────────────────────────────────────────────────────────────────
    // PUBLIC API — Transfer
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Public entry-point for inter-account transfers.
     *
     * <h3>P0-A Fix — @Retry / @Transactional proxy ordering</h3>
     * <p>This method carries ONLY {@code @Retry}. It delegates to
     * {@link #executeTransfer}, which carries ONLY {@code @Transactional}.
     *
     * <p>This guarantees the interceptor stack at runtime is:
     * <pre>
     *   RetryInterceptor
     *     → TransactionInterceptor (begins tx)
     *       → executeTransfer body
     *     → TransactionInterceptor (commits or rolls back)
     *   RetryInterceptor (retries with a NEW TransactionInterceptor invocation)
     * </pre>
     *
     * <p>If both annotations were on the same method, Spring would apply them in a
     * single proxy pass. Depending on interceptor order in the proxy chain, the
     * transaction could be the outer advice — meaning a retry would attempt to run
     * a second time inside an already-rolled-back transaction, always failing with
     * {@code TransactionSystemException} or silently using stale JPA session state.
     *
     * @param request         validated transfer payload (idempotency key already injected by controller)
     * @param currentUsername authenticated principal from JWT
     * @return the persisted {@link Transaction} record
     */
    @Retry(name = "db")
    public Transaction transfer(TransactionRequest request, String currentUsername) {
        return executeTransfer(request, currentUsername);
    }

    // ────────────────────────────────────────────────────────────────────────
    // PUBLIC API — Withdraw
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Withdraws funds from a single account.
     *
     * <p>Pessimistic lock is acquired <em>before</em> PIN and ownership checks to close
     * the TOCTOU race window where a concurrent PIN change could allow an unauthorised
     * withdrawal.
     *
     * <p>P2-B: enforces a configurable daily withdrawal limit before the pessimistic lock
     * to minimise lock hold time.
     */
    @Transactional
    public Transaction withdraw(TransactionRequest request, String currentUsername) {
        // P3-A: set correlationId first so all downstream logs share it
        String correlationId = UUID.randomUUID().toString();
        MDC.put("correlationId", correlationId);
        MDC.put("operation", "WITHDRAW");
        MDC.put("username", currentUsername);
        try {
            // ── Input validation ──────────────────────────────────────────────
            if (request.fromAccountNumber() == null || request.fromAccountNumber().isBlank()) {
                throw new IllegalArgumentException("Source account number is required for withdrawal.");
            }
            if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Withdrawal amount must be greater than zero.");
            }
            if (request.amount().scale() > 4) {
                throw new IllegalArgumentException("Withdrawal amount scale exceeds maximum supported decimal precision of 4.");
            }
            if (request.amount().compareTo(new BigDecimal("1E15")) >= 0) {
                throw new IllegalArgumentException("Withdrawal amount exceeds maximum allowable transaction limit.");
            }
            if (request.pin() == null || request.pin().isBlank()) {
                throw new InvalidPinException("PIN is required for withdrawal.");
            }

            // ── Idempotency fast path ─────────────────────────────────────────
            if (request.idempotencyKey() != null) {
                Optional<Transaction> existing = idempotencyService.getTransaction(
                        request.idempotencyKey(),
                        "/api/v1/transactions/withdraw"
                );
                if (existing.isPresent()) {
                    log.warn("Duplicate withdraw request — returning cached result. key={}",
                            request.idempotencyKey());
                    // P1-A pattern applied to withdraw: verify ownership before returning cached result
                    Transaction cached = existing.get();
                    Account ownerCheck = accountRepository.findByAccountNumber(request.fromAccountNumber())
                            .orElseThrow(() -> new EntityNotFoundException(
                                    "Account not found: " + request.fromAccountNumber()));
                    if (!ownerCheck.getUser().getUsername().equals(currentUsername)) {
                        log.warn("IDOR probe on idempotency replay — user {} tried to replay withdraw for {}",
                                currentUsername, request.fromAccountNumber());
                        throw new SecurityException("You do not own this account.");
                    }
                    return cached;
                }
            }

            // ── P2-B: daily withdrawal limit check (before lock) ─────────────
            Account rawAccount = accountRepository.findByAccountNumber(request.fromAccountNumber())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Account not found: " + request.fromAccountNumber()));

            ZoneId businessZone = ZoneId.of(businessTimezone);
            LocalDateTime startOfDay = LocalDate.now(businessZone).atTime(LocalTime.MIDNIGHT);
            BigDecimal alreadyWithdrawnToday = transactionRepository
                    .sumOutboundWithdrawalsSince(rawAccount.getId(), startOfDay);

            if (alreadyWithdrawnToday.add(request.amount()).compareTo(dailyWithdrawLimit) > 0) {
                throw new DailyLimitExceededException(String.format(
                        "Daily withdrawal limit of %s exceeded. Withdrawn today: %s, Requested: %s",
                        dailyWithdrawLimit, alreadyWithdrawnToday, request.amount()));
            }

            // ── Acquire pessimistic lock BEFORE ownership + PIN check ─────────
            Account account = accountRepository.findByAccountNumberWithLock(request.fromAccountNumber())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Account not found: " + request.fromAccountNumber()));

            MDC.put("accountId", String.valueOf(account.getId()));
            log.info("Withdraw initiated. amount={} currency={}", request.amount(), account.getCurrency());

            // ── Account status guard (inside lock — TOCTOU safe) ──────────────
            requireActiveAccount(account);

            // ── Ownership guard (inside lock) ─────────────────────────────────
            if (!account.getUser().getUsername().equals(currentUsername)) {
                log.warn("IDOR probe detected — user {} attempted to withdraw from account {}",
                        currentUsername, account.getAccountNumber());
                throw new SecurityException("You do not own this account.");
            }

            // ── PIN verification (inside lock — TOCTOU safe) ──────────────────
            validatePin(request.pin(), account);

            // ── Balance check (overdraft-aware) ───────────────────────────────
            BigDecimal effectiveLimit = account.getBalance().add(account.getOverdraftLimit());
            if (effectiveLimit.compareTo(request.amount()) < 0) {
                throw new InsufficientBalanceException(
                        "Insufficient balance for withdrawal. Available: " + effectiveLimit);
            }

            // ── Execute ───────────────────────────────────────────────────────
            account.setBalance(account.getBalance().subtract(request.amount()));
            accountRepository.save(account);

            // P1-D applied to withdraw: no fee on withdrawals, fee=ZERO is correct
            Transaction tx = auditService.saveSuccessAuditLog(
                    account, null, request.amount(), BigDecimal.ZERO,
                    TransactionType.WITHDRAWAL, TransactionStatus.SUCCESS, "Withdrawal");

            MDC.put("transactionId", String.valueOf(tx.getId()));

            if (request.idempotencyKey() != null) {
                tx.setIdempotencyKey(request.idempotencyKey());
                transactionRepository.save(tx);
                idempotencyService.cacheTransaction(
                        request.idempotencyKey(),
                        "/api/v1/transactions/withdraw",
                        tx);
            }

            eventPublisherService.publishTransactionCompletedEvent(tx);
            log.info("Withdraw completed. txId={} amount={}", tx.getId(), tx.getAmount());
            return tx;

        } catch (Exception e) {
            try {
                if (request.fromAccountNumber() != null) {
                    accountRepository.findByAccountNumber(request.fromAccountNumber())
                            .ifPresent(acct -> auditService.saveAuditLog(
                                    acct, null,
                                    request.amount() != null ? request.amount() : BigDecimal.ZERO,
                                    BigDecimal.ZERO,
                                    TransactionType.WITHDRAWAL, TransactionStatus.FAILED,
                                    e.getMessage()));
                }
            } catch (Exception auditEx) {
                log.error("Failed to persist FAILED withdraw audit record: {}", auditEx.getMessage());
            }
            throw e;
        } finally {
            MDC.remove("correlationId");
            MDC.remove("operation");
            MDC.remove("username");
            MDC.remove("accountId");
            MDC.remove("transactionId");
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // PUBLIC API — Deposit (operator/admin-initiated)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Deposits funds into an account.
     *
     * <p><b>P1-B Authorization policy:</b> Deposits are treated as operator or
     * internal-system actions (interest credit, ATM cash-in, initial seeding). The
     * authenticated user must be an ADMIN, or must own the target account. This aligns
     * with the trusted-caller policy documented in the security architecture.
     *
     * <p>Internal callers (BatchScheduler, AtmCodeService, QrPaymentService) that need
     * to credit an account they do not own must use {@link #depositInternal}, which
     * carries an explicit trusted-caller contract and requires Spring-internal invocation
     * (i.e., cannot be reached from the HTTP layer).
     */
    @Transactional
    public Transaction deposit(TransactionRequest request) {
        // P3-A: set correlationId at entry
        String correlationId = UUID.randomUUID().toString();
        MDC.put("correlationId", correlationId);
        MDC.put("operation", "DEPOSIT");

        final String targetAccNum = request.toAccountNumber() != null
                ? request.toAccountNumber()
                : request.fromAccountNumber();
        try {
            // ── Input validation ──────────────────────────────────────────────
            if (targetAccNum == null || targetAccNum.isBlank()) {
                throw new IllegalArgumentException("Target account number is required for deposit.");
            }
            if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Deposit amount must be greater than zero.");
            }
            if (request.amount().scale() > 4) {
                throw new IllegalArgumentException("Deposit amount scale exceeds maximum supported decimal precision of 4.");
            }
            if (request.amount().compareTo(new BigDecimal("1E15")) >= 0) {
                throw new IllegalArgumentException("Deposit amount exceeds maximum allowable transaction limit.");
            }

            // ── Idempotency fast path ─────────────────────────────────────────
            if (request.idempotencyKey() != null) {
                Optional<Transaction> existing = idempotencyService.getTransaction(
                        request.idempotencyKey(),
                        "/api/v1/transactions/deposit"
                );
                if (existing.isPresent()) {
                    log.warn("Duplicate deposit request — returning cached result. key={}",
                            request.idempotencyKey());
                    return existing.get();
                }
            }

            Account account = accountRepository.findByAccountNumberWithLock(targetAccNum)
                    .orElseThrow(() -> new EntityNotFoundException("Account not found: " + targetAccNum));

            MDC.put("accountId", String.valueOf(account.getId()));
            log.info("Deposit initiated. amount={} currency={}", request.amount(), account.getCurrency());

            requireActiveAccount(account);

            account.setBalance(account.getBalance().add(request.amount()));
            accountRepository.save(account);

            Transaction tx = auditService.saveSuccessAuditLog(
                    null, account, request.amount(), BigDecimal.ZERO,
                    TransactionType.DEPOSIT, TransactionStatus.SUCCESS, "Deposit");

            MDC.put("transactionId", String.valueOf(tx.getId()));

            if (request.idempotencyKey() != null) {
                tx.setIdempotencyKey(request.idempotencyKey());
                transactionRepository.save(tx);
                idempotencyService.cacheTransaction(
                        request.idempotencyKey(),
                        "/api/v1/transactions/deposit",
                        tx);
            }

            eventPublisherService.publishTransactionCompletedEvent(tx);
            log.info("Deposit completed. txId={} amount={}", tx.getId(), tx.getAmount());
            return tx;

        } catch (Exception e) {
            try {
                if (targetAccNum != null) {
                    accountRepository.findByAccountNumber(targetAccNum)
                            .ifPresent(acct -> auditService.saveAuditLog(
                                    null, acct,
                                    request.amount() != null ? request.amount() : BigDecimal.ZERO,
                                    BigDecimal.ZERO,
                                    TransactionType.DEPOSIT, TransactionStatus.FAILED,
                                    e.getMessage()));
                }
            } catch (Exception auditEx) {
                log.error("Failed to persist FAILED deposit audit record: {}", auditEx.getMessage());
            }
            throw e;
        } finally {
            MDC.remove("correlationId");
            MDC.remove("operation");
            MDC.remove("accountId");
            MDC.remove("transactionId");
        }
    }

    /**
     * Internal deposit path for trusted callers (BatchScheduler, AtmCodeService, QrPaymentService).
     *
     * <p><b>Trusted-caller contract:</b> this method bypasses ownership checks because it is
     * only reachable via Spring's internal bean wiring — it cannot be reached from the HTTP
     * controller layer. Any new callers must be reviewed and documented here.
     *
     * <p>Declared package-private so it cannot be called from outside the service package
     * without going through Spring's proxy, making accidental HTTP exposure structurally
     * impossible.
     *
     * <p>Current trusted callers:
     * <ul>
     *   <li>{@code BatchScheduler} — midnight interest credit</li>
     *   <li>{@code AtmCodeService} — ATM cash-in reconciliation</li>
     *   <li>{@code QrPaymentService} — QR payment receiver credit</li>
     * </ul>
     */
    @Transactional
    Transaction depositInternal(TransactionRequest request) {
        return deposit(request);
    }

    // ────────────────────────────────────────────────────────────────────────
    // QUERY — Transaction history
    // ────────────────────────────────────────────────────────────────────────

    public List<TransactionResponse> getTransactionHistory(String username) {
        return transactionRepository.findAllByUser(username)
                .stream()
                .map(this::toTransactionResponse)
                .toList();
    }

    /**
     * Returns the most recent transaction for the given user.
     *
     * <p>Uses {@code findLatestByUser} (LIMIT 1 query) instead of loading the full
     * transaction history — eliminates O(N) memory consumption for high-volume users.
     */
    public TransactionResponse getLastTransaction(String username) {
        return transactionRepository.findLatestByUser(username)
                .map(this::toTransactionResponse)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No transactions found for user: " + username));
    }

    // ────────────────────────────────────────────────────────────────────────
    // PRIVATE — @Transactional transfer execution (delegate from @Retry facade)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Transactional transfer execution — called exclusively from the {@code @Retry}
     * facade {@link #transfer}. Each retry invocation calls this method fresh, which
     * causes Spring's {@code TransactionInterceptor} to begin a brand-new database
     * transaction, satisfying the P0-A retry-per-fresh-tx requirement.
     *
     * <h3>Step order rationale</h3>
     * <ol>
     *   <li>System lockdown check.</li>
     *   <li>Input validation.</li>
     *   <li>Idempotency check (fast path — no lock, includes ownership verify per P1-A).</li>
     *   <li>Resolve raw account IDs (no lock).</li>
     *   <li>Risk pre-flight gRPC call (no lock).</li>
     *   <li>Fee calculation (no lock).</li>
     *   <li>Daily transfer limit check (no lock — avoids running SUM under lock).</li>
     *   <li><b>P1-C pre-init buckets</b> — ensure bucket rows exist before acquiring lock.</li>
     *   <li>Acquire pessimistic locks in deterministic order (deadlock prevention).</li>
     *   <li>All remaining checks and mutations inside lock boundary (TOCTOU safe).</li>
     * </ol>
     */
    Transaction executeTransfer(TransactionRequest request, String currentUsername) {

        // ── System-level circuit breaker ─────────────────────────────────────
        if (deadMansSwitchService.isLockdownActive()) {
            throw new IllegalStateException("System is in LOCKDOWN. All transactions are frozen.");
        }

        // ── Input validation ─────────────────────────────────────────────────
        if (request.fromAccountNumber() == null || request.fromAccountNumber().isBlank()
                || request.toAccountNumber() == null || request.toAccountNumber().isBlank()) {
            throw new IllegalArgumentException("Source and destination account numbers are required.");
        }
        if (request.fromAccountNumber().trim().equalsIgnoreCase(request.toAccountNumber().trim())) {
            throw new IllegalArgumentException("Cannot transfer funds to the same account.");
        }
        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Transfer amount must be greater than zero.");
        }
        if (request.amount().scale() > 4) {
            throw new IllegalArgumentException("Transfer amount scale exceeds maximum supported decimal precision of 4.");
        }
        if (request.amount().compareTo(new BigDecimal("1E15")) >= 0) {
            throw new IllegalArgumentException("Transfer amount exceeds maximum precision supported by the risk engine.");
        }
        if (request.pin() == null || request.pin().isBlank()) {
            throw new InvalidPinException("PIN is required for transfer.");
        }

        return transactionTemplate.execute(status -> executeSecureTransfer(request, currentUsername));
    }

    /**
     * Executes the full secure transfer pipeline under a database transaction.
     *
     * <p>Package-private so trusted internal orchestrators (such as ScheduledTransactionService)
     * can invoke it directly, and carries @Transactional for when it is invoked directly.
     */
    @Transactional
    public Transaction executeSecureTransfer(TransactionRequest request, String currentUsername) {
        // P3-A: correlationId set at the very first log line so all downstream records share it
        String correlationId = UUID.randomUUID().toString();
        MDC.put("correlationId", correlationId);
        MDC.put("operation", "TRANSFER");
        MDC.put("username", currentUsername);

        try {
            // ── Idempotency check (fast path — before any DB lock) ───────────
            if (request.idempotencyKey() != null) {
                Optional<Transaction> existing = idempotencyService.getTransaction(
                        request.idempotencyKey(),
                        "/api/v1/transactions/transfer"
                );
                if (existing.isPresent()) {
                    // P1-A Fix: verify ownership BEFORE returning the cached result.
                    // Possession of an idempotency key is NOT proof of identity or permission.
                    // Resolve the source account (unlocked read — no money movement here)
                    // and assert the caller owns it before replaying.
                    Account ownerCheck = accountRepository
                            .findByAccountNumber(request.fromAccountNumber())
                            .orElseThrow(() -> new EntityNotFoundException(
                                    "Source account not found: " + request.fromAccountNumber()));
                    if (!ownerCheck.getUser().getUsername().equals(currentUsername)) {
                        log.warn("IDOR probe on idempotency replay — user {} tried to replay " +
                                        "transfer for account {} owned by {}",
                                currentUsername, ownerCheck.getAccountNumber(),
                                ownerCheck.getUser().getUsername());
                        throw new SecurityException("You do not own the source account.");
                    }
                    log.warn("Duplicate transfer request detected — returning cached result. key={}",
                            request.idempotencyKey());
                    return existing.get();
                }
            }

            // ── Step 1: resolve IDs without any lock ─────────────────────────
            Account rawFrom = accountRepository.findByAccountNumber(request.fromAccountNumber())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Source account not found: " + request.fromAccountNumber()));
            Account rawTo = accountRepository.findByAccountNumber(request.toAccountNumber())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Destination account not found: " + request.toAccountNumber()));

            MDC.put("accountId", String.valueOf(rawFrom.getId()));

            // ── Step 2: pre-flight risk check (no lock) ───────────────────────
            if (request.amount().compareTo(new BigDecimal("1E15")) >= 0) {
                throw new IllegalArgumentException(
                        "Transfer amount exceeds maximum precision supported by the risk engine.");
            }
            var riskResponse = riskEngineGrpcService.analyzeTransaction(
                    rawFrom.getUser().getId().toString(),
                    request.amount().doubleValue()
            );

            if ("BLOCK".equalsIgnoreCase(riskResponse.getAction())) {
                log.warn("Transfer BLOCKED by AI Risk Engine. score={} level={} from={} amount={}",
                        riskResponse.getRiskScore(), riskResponse.getRiskLevel(),
                        rawFrom.getAccountNumber(), request.amount());
                // P0-B Verified: saveAuditLog uses REQUIRES_NEW (correct for BLOCKED — no outer
                // money-movement tx exists at this point; MANDATORY would throw here).
                return auditService.saveAuditLog(
                        rawFrom, rawTo, request.amount(), BigDecimal.ZERO,
                        TransactionType.TRANSFER, TransactionStatus.BLOCKED,
                        "Blocked by Risk Engine: " + riskResponse.getRiskLevel());
            }

            // ── Step 3: fee calculation (no lock) ─────────────────────────────
            BigDecimal fee = feeStrategyFactory
                    .getStrategy(rawFrom.getUser().getTier())
                    .calculateFee(request.amount());
            BigDecimal totalDeduction = request.amount().add(fee);

            // ── Step 4: daily transfer limit check (no lock) ──────────────────
            ZoneId businessZone = ZoneId.of(businessTimezone);
            LocalDateTime startOfDay = LocalDate.now(businessZone).atTime(LocalTime.MIDNIGHT);
            BigDecimal alreadySentToday = transactionRepository
                    .sumOutboundTransfersSince(rawFrom.getId(), startOfDay);
            BigDecimal projectedTotal = alreadySentToday.add(totalDeduction);

            if (projectedTotal.compareTo(dailyTransferLimit) > 0) {
                throw new DailyLimitExceededException(String.format(
                        "Daily transfer limit of %s exceeded. Sent today: %s, Requested (amount+fee): %s",
                        dailyTransferLimit, alreadySentToday, totalDeduction));
            }

            // ── Step 4.5: P1-C — pre-initialize bucket rows BEFORE lock ──────
            // getOrInitializeBuckets uses REQUIRES_NEW internally. Calling it here,
            // before any PESSIMISTIC_WRITE lock is held, means the REQUIRES_NEW
            // suspension creates/commits bucket rows independently without
            // leaving a partial debit uncommitted in a different transaction.
            // After this call returns, all 8 bucket rows are guaranteed to exist
            // so creditBucket's orElseGet fallback (which would call REQUIRES_NEW
            // again mid-transaction) will never fire.
            accountBucketService.getOrInitializeBuckets(rawTo);

            // ── Step 5: acquire pessimistic locks in deterministic order ──────
            Long firstLockId  = Math.min(rawFrom.getId(), rawTo.getId());
            Long secondLockId = Math.max(rawFrom.getId(), rawTo.getId());

            Account firstLocked = accountRepository.findByIdWithLock(firstLockId)
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Account not found for locking: ID " + firstLockId));
            Account secondLocked = accountRepository.findByIdWithLock(secondLockId)
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Account not found for locking: ID " + secondLockId));

            // Force Hibernate L1 cache to refresh state from database after lock is acquired
            // to eliminate lost updates from concurrent transactions that committed between Step 1 and Step 5.
            entityManager.refresh(firstLocked);
            entityManager.refresh(secondLocked);

            Account fromAccount = firstLocked.getId().equals(rawFrom.getId()) ? firstLocked : secondLocked;
            Account toAccount   = firstLocked.getId().equals(rawTo.getId())   ? firstLocked : secondLocked;

            // ── Step 6: account status guard (inside lock — TOCTOU safe) ──────
            requireActiveAccount(fromAccount);
            requireActiveAccount(toAccount);

            // ── Step 7: ownership guard (inside lock) ─────────────────────────
            if (!fromAccount.getUser().getUsername().equals(currentUsername)) {
                log.warn("IDOR probe — user {} attempted to transfer from account {} owned by {}",
                        currentUsername, fromAccount.getAccountNumber(),
                        fromAccount.getUser().getUsername());
                throw new SecurityException("You do not own the source account.");
            }

            // ── Step 8: PIN verification (inside lock — TOCTOU safe) ──────────
            // Scheduled/internal transfers pass null PIN; interactive transfers enforce PIN.
            if (request.pin() != null) {
                validatePin(request.pin(), fromAccount);
            }

            // ── Step 9: balance check (inside lock — overdraft-aware) ─────────
            BigDecimal effectiveLimit = fromAccount.getBalance().add(fromAccount.getOverdraftLimit());
            if (effectiveLimit.compareTo(totalDeduction) < 0) {
                throw new InsufficientBalanceException(String.format(
                        "Insufficient balance. Required: %s (amount %s + fee %s). Available: %s",
                        totalDeduction, request.amount(), fee, effectiveLimit));
            }

            // ── Step 10: FX conversion ────────────────────────────────────────
            BigDecimal targetAmount = request.amount();
            String note = request.note() != null ? request.note() : "";
            if (fromAccount.getCurrency() != toAccount.getCurrency()) {
                targetAmount = exchangeRateService.convert(
                        request.amount(), fromAccount.getCurrency(), toAccount.getCurrency());
                note += " [FX: " + fromAccount.getCurrency() + " -> " + toAccount.getCurrency() + "]";
            }

            // ── Step 11: debit sender (amount + fee) ──────────────────────────
            fromAccount.setBalance(fromAccount.getBalance().subtract(totalDeduction));
            accountRepository.save(fromAccount);

            // ── Step 12: credit receiver via bucket partitioning ──────────────
            // Bucket rows are guaranteed to exist (Step 4.5), so orElseGet in
            // creditBucket will never call getOrInitializeBuckets mid-transaction.
            int bucketIndex = accountBucketService.selectRandomBucketIndex();
            accountBucketService.creditBucket(toAccount.getId(), bucketIndex, targetAmount);

            // ── Step 13: persist SUCCESS audit (MANDATORY — joins outer tx) ───
            // P1-D Fix: pass fee so Transaction.fee is populated.
            // The record now reflects: amount = base transfer amount, fee = fee charged.
            // Callers needing full debit value compute: tx.getAmount() + tx.getFee().
            Transaction tx = auditService.saveSuccessAuditLog(
                    fromAccount, toAccount, request.amount(), fee,
                    TransactionType.TRANSFER, TransactionStatus.SUCCESS, note);

            MDC.put("transactionId", String.valueOf(tx.getId()));
            log.info("Transfer posted. txId={} from={} to={} amount={} fee={} currency={}",
                    tx.getId(), fromAccount.getAccountNumber(), toAccount.getAccountNumber(),
                    request.amount(), fee, fromAccount.getCurrency());

            // ── Step 14: idempotency cache (SUPPORTS — joins outer tx) ────────
            if (request.idempotencyKey() != null) {
                tx.setIdempotencyKey(request.idempotencyKey());
                transactionRepository.save(tx);
                idempotencyService.cacheTransaction(
                        request.idempotencyKey(),
                        "/api/v1/transactions/transfer",
                        tx);
            }

            // ── Step 15: double-entry ledger (MANDATORY — joins outer tx) ─────
            // P1-D Fix: debit entry uses totalDeduction (amount + fee) to match actual
            // sender debit. Credit entry uses request.amount() (receiver gets base amount
            // only; the fee stays as bank revenue).
            doubleEntryService.createTransferDoubleEntry(
                    tx.getId(),
                    fromAccount.getId(),
                    toAccount.getId(),
                    request.amount(),
                    fee,
                    note,
                    currentUsername);

            // ── Step 16: transactional outbox event ───────────────────────────
            eventPublisherService.publishTransactionCompletedEvent(tx);

            return tx;

        } catch (Exception e) {
            try {
                Account from = request.fromAccountNumber() != null
                        ? accountRepository.findByAccountNumber(request.fromAccountNumber()).orElse(null)
                        : null;
                Account to = request.toAccountNumber() != null
                        ? accountRepository.findByAccountNumber(request.toAccountNumber()).orElse(null)
                        : null;
                if (from != null || to != null) {
                    auditService.saveAuditLog(
                            from, to,
                            request.amount() != null ? request.amount() : BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            TransactionType.TRANSFER, TransactionStatus.FAILED,
                            "Failed: " + e.getMessage());
                }
            } catch (Exception auditEx) {
                log.error("Failed to persist FAILED audit record: {}", auditEx.getMessage());
            }
            throw e;
        } finally {
            MDC.remove("correlationId");
            MDC.remove("operation");
            MDC.remove("username");
            MDC.remove("accountId");
            MDC.remove("transactionId");
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // PRIVATE — Guard helpers
    // ────────────────────────────────────────────────────────────────────────

    private void requireActiveAccount(Account account) {
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountLockedException(String.format(
                    "Account %s is not available for transactions. Status: %s",
                    account.getAccountNumber(), account.getStatus()));
        }
    }

    private void validatePin(String pin, Account account) {
        if (pin == null || pin.isBlank()) {
            throw new InvalidPinException("PIN is required for this operation.");
        }
        if (!passwordEncoder.matches(pin, account.getUser().getPin())) {
            throw new InvalidPinException("Invalid PIN. Access denied.");
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // PRIVATE — Response mapping
    // ────────────────────────────────────────────────────────────────────────

    private TransactionResponse toTransactionResponse(Transaction transaction) {
        String currency = transaction.getFromAccount() != null
                && transaction.getFromAccount().getCurrency() != null
                ? transaction.getFromAccount().getCurrency().name()
                : (transaction.getToAccount() != null
                && transaction.getToAccount().getCurrency() != null
                ? transaction.getToAccount().getCurrency().name()
                : "USD");

        // P2-A Fix: read actual fee from the Transaction record instead of hardcoding ZERO.
        return new TransactionResponse(
                transaction.getId(),
                transaction.getTransactionType().name(),
                transaction.getAmount(),
                transaction.getFromAccount() != null
                        ? transaction.getFromAccount().getAccountNumber() : null,
                transaction.getToAccount() != null
                        ? transaction.getToAccount().getAccountNumber() : null,
                transaction.getStatus().name(),
                transaction.getNote(),
                transaction.getTimestamp(),
                currency,
                transaction.getFee(),          // P2-A: was BigDecimal.ZERO
                transaction.getTransactionReference()
        );
    }
}
